package com.ownsudo.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ownsudo.entity.*;
import com.ownsudo.repository.*;
import com.ownsudo.service.HybridRagService;
import com.ownsudo.service.LlmClientService;
import com.ownsudo.service.ToolExecutor;
import com.ownsudo.service.AgentMemoryService;
import com.ownsudo.service.ExactHashCacheService;
import com.ownsudo.service.ModelGatewayService;
import com.ownsudo.service.ChatRunRecorder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;


@RestController
@RequestMapping("/api")
public class AgentPlatformController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AgentPlatformController.class);

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private ChatSessionRepository sessionRepository;

    @Autowired
    private ChatMessageRepository messageRepository;

    @Autowired
    private KnowledgeChunkRepository knowledgeRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private McpToolRepository toolRepository;

    @Autowired
    private com.ownsudo.security.ApprovalAuthorizationService approvalAuthorizationService;

    @Autowired
    private LlmClientService llmClientService;

    @Autowired
    private HybridRagService hybridRagService;

    @Autowired
    private ToolExecutor toolExecutor;

    @Autowired(required = false)
    private ApprovalTicketRepository approvalTicketRepository;

    @Autowired(required = false)
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private AgentMemoryService agentMemoryService;

    @Autowired
    private ExactHashCacheService exactHashCacheService;

    @Autowired
    private ModelGatewayService modelGatewayService;

    @Autowired
    private ChatRunRecorder chatRunRecorder;

    @Autowired(required = false)
    private com.ownsudo.repository.WorkflowDefinitionRepository workflowDefinitionRepository;

    @Autowired(required = false)
    private com.ownsudo.repository.GitRepositoryEntityRepository gitRepositoryEntityRepository;

    @Autowired(required = false)
    private com.ownsudo.service.GitService gitService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    // ── 生产级风控防线：每个 IP 每日最多 5 轮，全站每日演示预算上限 1.00 元 ──
    private final ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> dailyIpUsageMap = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger dailyTotalTokens = new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile String currentQuotaDate = LocalDate.now().toString();

    private static final int DEFAULT_MAX_ROUNDS_PER_IP = 5;
    private static final int DEFAULT_DAILY_TOKEN_LIMIT = 100_000; // 约合 ¥1.00 (通义千问 qwen-plus 算力额度)

    private void checkAndResetDailyQuota() {
        String today = LocalDate.now().toString();
        if (!today.equals(currentQuotaDate)) {
            synchronized (this) {
                if (!today.equals(currentQuotaDate)) {
                    currentQuotaDate = today;
                    dailyIpUsageMap.clear();
                    dailyTotalTokens.set(0);
                    log.info("[风控中枢] 新的一天到达 ({})，已自动重置 IP 提问额度与全站每日算力账单", today);
                }
            }
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
        if (request == null) return "127.0.0.1";
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
            return ip.split(",")[0].trim();
        }
        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
            return ip.trim();
        }
        String remote = request.getRemoteAddr();
        return (remote != null && !remote.isBlank()) ? remote : "127.0.0.1";
    }

    @GetMapping("/system/quota-status")
    public Map<String, Object> getQuotaStatus(HttpServletRequest servletRequest) {
        checkAndResetDailyQuota();
        String clientIp = resolveClientIp(servletRequest);
        String ipKey = currentQuotaDate + ":" + clientIp;
        int currentIpRounds = dailyIpUsageMap.computeIfAbsent(ipKey, k -> new java.util.concurrent.atomic.AtomicInteger(0)).get();
        int maxRoundsPerIp = DEFAULT_MAX_ROUNDS_PER_IP;
        try {
            String cfgVal = llmClientService.getConfig("RATE_LIMIT_PER_IP_DAILY", String.valueOf(DEFAULT_MAX_ROUNDS_PER_IP));
            maxRoundsPerIp = Integer.parseInt(cfgVal);
        } catch (Exception ignored) {}

        int totalTokensToday = dailyTotalTokens.get();
        double estimatedCostRmb = (totalTokensToday / 1000.0) * 0.01;

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("date", currentQuotaDate);
        res.put("clientIp", clientIp);
        res.put("ipUsedRounds", currentIpRounds);
        res.put("ipMaxRounds", maxRoundsPerIp);
        res.put("ipRemainingRounds", Math.max(0, maxRoundsPerIp - currentIpRounds));
        res.put("totalTokensToday", totalTokensToday);
        res.put("estimatedCostRmb", String.format(Locale.US, "%.3f", estimatedCostRmb));
        res.put("dailyBudgetCapRmb", 1.00);
        res.put("isBudgetExceeded", totalTokensToday >= DEFAULT_DAILY_TOKEN_LIMIT);
        return res;
    }

    @PostMapping("/evaluation/judge")
    public ResponseEntity<?> judgeAgentRun(@RequestBody(required = false) Map<String, Object> req) {
        try {
            String task = String.valueOf(req == null ? "" : req.getOrDefault("task", ""));
            String answer = String.valueOf(req == null ? "" : req.getOrDefault("answer", ""));
            String evidence = objectMapper.writeValueAsString(req == null ? Map.of() : req.getOrDefault("evidence", Map.of()));
            String rubric = String.valueOf(req == null ? "" : req.getOrDefault("rubric", "任务完成度、指令遵循、回答正确性、工具使用合理性"));
            var scores = llmClientService.judgeAgentRun(task, answer, evidence, rubric);
            return ResponseEntity.ok(Map.of("scores", scores, "judgeModel", llmClientService.getConfig("EVAL_JUDGE_MODEL", llmClientService.getConfig("LLM_MODEL", "configured-model"))));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", Map.of("code", "JUDGE_FAILED", "message", e.getMessage() == null ? "Judge provider unavailable" : e.getMessage())));
        }
    }
    @GetMapping("/agents")
    public List<AgentEntity> getAgents() {
        List<AgentEntity> list = agentRepository.findAllByOrderByCreatedAtDesc();
        int legacyToolCount = toolExecutor.getToolDefinitions().size();
        int kbCount = (int) knowledgeRepository.count();
        for (AgentEntity agent : list) {
            if (agent.getSelectedToolsJson() == null || agent.getSelectedToolsJson().isBlank()) {
                agent.setToolsCount(legacyToolCount);
            } else {
                try {
                    Set<?> selected = objectMapper.readValue(agent.getSelectedToolsJson(),
                            objectMapper.getTypeFactory().constructCollectionType(Set.class, String.class));
                    agent.setToolsCount(selected.size());
                } catch (Exception ignored) {
                    agent.setToolsCount(0);
                }
            }
            if (agent.getKbCount() == null || agent.getKbCount() == 0) agent.setKbCount(kbCount);
        }
        return list;
    }

    @GetMapping("/agents/available-tools")
    public List<Map<String, Object>> getAvailableAgentTools() {
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> disabledTools = toolRepository.findAll().stream()
                .filter(tool -> !Boolean.TRUE.equals(tool.getEnabled()))
                .map(McpTool::getFunctionName).filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        for (Map<String, Object> definition : toolExecutor.getToolDefinitions()) {
            Object function = definition.get("function");
            if (!(function instanceof Map<?, ?> details)) continue;
            Object name = details.get("name");
            if (name == null || name.toString().isBlank() || disabledTools.contains(name.toString())) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name.toString());
            Object description = details.get("description");
            item.put("description", description == null ? "" : description.toString());
            result.add(item);
        }
        return result;
    }

    @PostMapping("/agents")
    public AgentEntity saveAgent(@RequestBody AgentEntity agent) {
        if (agent.getId() == null || agent.getId().trim().isEmpty()) {
            agent.setId("agent-" + UUID.randomUUID().toString().substring(0, 8));
        }
        if (agent.getSelectedToolsJson() != null && !agent.getSelectedToolsJson().isBlank()) {
            try {
                Set<String> selected = objectMapper.readValue(agent.getSelectedToolsJson(),
                        objectMapper.getTypeFactory().constructCollectionType(LinkedHashSet.class, String.class));
                Set<String> disabled = toolRepository.findAll().stream()
                        .filter(tool -> !Boolean.TRUE.equals(tool.getEnabled()))
                        .map(McpTool::getFunctionName).filter(Objects::nonNull)
                        .collect(java.util.stream.Collectors.toSet());
                Set<String> available = toolExecutor.getToolDefinitions().stream()
                        .map(definition -> (Map<?, ?>) definition.get("function"))
                        .filter(Objects::nonNull).map(details -> String.valueOf(details.get("name")))
                        .filter(name -> !disabled.contains(name))
                        .collect(java.util.stream.Collectors.toSet());
                if (!available.containsAll(selected)) throw new IllegalArgumentException("Agent contains unknown or disabled tool names");
                agent.setSelectedToolsJson(objectMapper.writeValueAsString(selected));
                agent.setToolsCount(selected.size());
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException("selectedToolsJson must be a JSON string array", e);
            }
        }
        return agentRepository.save(agent);
    }

    @DeleteMapping("/agents/{id}")
    public ResponseEntity<Void> deleteAgent(@PathVariable String id) {
        agentRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/chat/sessions")
    public List<ChatSessionEntity> getSessions() {
        return sessionRepository.findAllByOrderByUpdatedAtDesc();
    }

    @PostMapping("/chat/sessions")
    public ChatSessionEntity createSession(@RequestBody(required = false) Map<String, String> body) {
        String title = (body != null && body.containsKey("title")) ? body.get("title") : "新会话";
        ChatSessionEntity s = new ChatSessionEntity();
        s.setId("s-" + UUID.randomUUID().toString().substring(0, 8));
        s.setTitle(title);
        return sessionRepository.save(s);
    }

    @DeleteMapping("/chat/sessions/{id}")
    public ResponseEntity<Void> deleteSession(@PathVariable String id) {
        messageRepository.deleteBySessionId(id);
        sessionRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/chat/sessions/{id}/messages")
    public List<ChatMessageEntity> getSessionMessages(@PathVariable String id) {
        return messageRepository.findBySessionIdOrderByCreatedAtAsc(id);
    }

    @GetMapping("/chat/messages")
    public List<ChatMessageEntity> getMessagesByQuery(@RequestParam(name = "sessionId", required = false) String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return Collections.emptyList();
        return messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestBody Map<String, Object> req, HttpServletRequest servletRequest) {
        SseEmitter emitter = new SseEmitter(120_000L);
        long startTime = System.currentTimeMillis();

        checkAndResetDailyQuota();
        final String clientIp = resolveClientIp(servletRequest);

        int maxRoundsPerIp = DEFAULT_MAX_ROUNDS_PER_IP;
        try {
            String cfgVal = llmClientService.getConfig("RATE_LIMIT_PER_IP_DAILY", String.valueOf(DEFAULT_MAX_ROUNDS_PER_IP));
            maxRoundsPerIp = Integer.parseInt(cfgVal);
        } catch (Exception ignored) {}

        int dailyTokenLimit = DEFAULT_DAILY_TOKEN_LIMIT;
        try {
            String cfgVal = llmClientService.getConfig("DAILY_TOKEN_LIMIT", String.valueOf(DEFAULT_DAILY_TOKEN_LIMIT));
            dailyTokenLimit = Integer.parseInt(cfgVal);
        } catch (Exception ignored) {}

        final boolean isDailyBudgetExceeded = dailyTotalTokens.get() >= dailyTokenLimit;
        final boolean isLocalDev = "127.0.0.1".equals(clientIp) || "0:0:0:0:0:0:0:1".equals(clientIp) || "localhost".equalsIgnoreCase(clientIp);
        final String ipKey = currentQuotaDate + ":" + clientIp;
        final int currentIpRounds = dailyIpUsageMap.computeIfAbsent(ipKey, k -> new java.util.concurrent.atomic.AtomicInteger(0)).get();
        final boolean isIpLimitExceeded = !isLocalDev && currentIpRounds >= maxRoundsPerIp;

        String sessionId = (String) req.getOrDefault("sessionId", "s-" + UUID.randomUUID().toString().substring(0, 8));
        String messageText = (String) req.getOrDefault("message", "");
        String agentId = (String) req.get("agentId");
        
        // 1. 真实使 agentId 数据库记录生效
        String dbModel = null;
        String dbSystemPrompt = null;
        Double dbTemperature = null;
        AtomicReference<Set<String>> allowedToolNames = new AtomicReference<>(null);
        Set<String> disabledToolNames = toolRepository.findAll().stream()
                .filter(tool -> !Boolean.TRUE.equals(tool.getEnabled()))
                .map(McpTool::getFunctionName).filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        String agentName = "ownagent 调度中枢";

        if (agentId != null && !agentId.isBlank()) {
            Optional<AgentEntity> agentOpt = agentRepository.findById(agentId);
            if (agentOpt.isPresent()) {
                AgentEntity a = agentOpt.get();
                agentName = a.getName();
                dbModel = a.getModel();
                dbSystemPrompt = a.getSystemPrompt();
                dbTemperature = a.getTemperature();
                if (a.getSelectedToolsJson() != null && !a.getSelectedToolsJson().isBlank()) {
                    try {
                        allowedToolNames.set(objectMapper.readValue(a.getSelectedToolsJson(),
                                objectMapper.getTypeFactory().constructCollectionType(LinkedHashSet.class, String.class)));
                    } catch (Exception invalidSelection) {
                        log.warn("Agent {} has invalid selected tool configuration; disabling tools for this run", agentId, invalidSelection);
                        allowedToolNames.set(Collections.emptySet());
                    }
                }
            }
        }

        String rawModel = (req.get("model") != null && !((String)req.get("model")).isBlank()) ? (String) req.get("model") : dbModel;
        String rawSystemPrompt = (req.get("systemPrompt") != null && !((String)req.get("systemPrompt")).isBlank()) ? (String) req.get("systemPrompt") : dbSystemPrompt;
        Double temperature = req.get("temperature") != null ? Double.parseDouble(req.get("temperature").toString()) : (dbTemperature != null ? dbTemperature : 0.7);

        // 1.5. 人机交互层 (UACOS) - 全自动意图识别与业务流动态分派 (Intent Router)
        com.ownsudo.entity.WorkflowDefinition matchedWorkflow = null;
        String matchedIntentReason = "通用意图直通模式";

        com.ownsudo.entity.GitRepositoryEntity boundRepo = null;

        String lowerText = messageText != null ? messageText.toLowerCase(Locale.ROOT) : "";
        boolean isGitOrCodeTask = lowerText.contains("git") ||
                lowerText.contains("commit") ||
                lowerText.contains("状态") ||
                lowerText.contains("排查") ||
                lowerText.contains("最新") ||
                lowerText.contains("分支") ||
                lowerText.contains("diff") ||
                lowerText.contains("提交") ||
                lowerText.contains("审查") ||
                lowerText.contains("代码") ||
                lowerText.contains("仓库") ||
                lowerText.contains("status") ||
                lowerText.contains("log");

        if (workflowDefinitionRepository != null) {
            List<com.ownsudo.entity.WorkflowDefinition> allWfs = workflowDefinitionRepository.findAllByOrderBySortOrderAscCreatedAtDesc();
            for (com.ownsudo.entity.WorkflowDefinition wf : allWfs) {
                boolean hit = false;
                if (wf.getTags() != null) {
                    for (String tag : wf.getTags().split("[,，]")) {
                        if (!tag.trim().isEmpty() && lowerText.contains(tag.trim().toLowerCase())) {
                            hit = true;
                            matchedIntentReason = "语义标签命中 [" + tag.trim() + "]";
                            break;
                        }
                    }
                }
                if (!hit && wf.getName() != null && lowerText.contains(wf.getName().toLowerCase())) {
                    hit = true;
                    matchedIntentReason = "名称特征命中 [" + wf.getName() + "]";
                }
                if (!hit && (wf.getId().contains("git") || "代码协同".equals(wf.getCategory()))) {
                    if (isGitOrCodeTask) {
                        hit = true;
                        matchedIntentReason = "核心业务实体命中 [Git 代码仓库与 Commit 审计特征]";

                    }
                }
                if (hit) {
                    matchedWorkflow = wf;
                    isGitOrCodeTask = true;
                    break;
                }
            }
        }

        // 绑定数据源与动态参数注水 (Context Hydration)
        if (matchedWorkflow != null) {
            agentName = matchedWorkflow.getName();
            if (gitRepositoryEntityRepository != null) {
                List<com.ownsudo.entity.GitRepositoryEntity> repos = gitRepositoryEntityRepository.findByWorkflowIdOrGlobalOrderByCreatedAtDesc(matchedWorkflow.getId());
                if (repos.isEmpty()) repos = gitRepositoryEntityRepository.findAll();
                if (!repos.isEmpty()) {
                    boundRepo = repos.get(0);
                }
            }
            if (rawSystemPrompt == null || rawSystemPrompt.isBlank()) {
                String tmpl = matchedWorkflow.getPromptTemplate() != null ? matchedWorkflow.getPromptTemplate() : "";
                if (boundRepo != null) {
                    tmpl = tmpl.replace("{{repo_name}}", boundRepo.getName() != null ? boundRepo.getName() : "builder");
                    tmpl = tmpl.replace("{{branch}}", boundRepo.getBranch() != null ? boundRepo.getBranch() : "main");
                    tmpl = tmpl.replace("{{latest_commit}}", boundRepo.getLastCommitSha() != null ? boundRepo.getLastCommitSha() : "HEAD");
                }
                rawSystemPrompt = tmpl;
            }
            if (boundRepo != null) {
                rawSystemPrompt += "\n\n【系统级工具授权提示】：已为你自动挂载受控代码仓库（仓库名：" + boundRepo.getName() + "，主分支：" + boundRepo.getBranch() + "）。当用户要求查看更新记录、提交历史、代码差异时，你必须直接调用系统提供的 get_git_commit_history 或 get_git_commit_diff 工具获取真实数据后再生成审计结论！";
            }
        }

        // 仅在明确是代码/Git任务时才注入代码仓库汇报规范；日常对话与通用问答保持温暖自然交流
        if (isGitOrCodeTask) {
            rawSystemPrompt = (rawSystemPrompt != null ? rawSystemPrompt : "") + "\n\n" +
                "【最终回答呈现规范（面向管理层与业务客户）】：\n" +
                "1. 【通俗大白话】：用人人都看得懂的中文大白话作答！严禁输出底层机器编号（如 repo-0aac5a67）、生僻参数（如 commit_sha=xxx、filePath）或未格式化的机器字符串；\n" +
                "2. 【结构化排版】：严格使用 Markdown 小标题（###）、清晰换行与重点列表（- ），严禁把多项内容黏在同一行或输出单行混乱表格；\n" +
                "3. 【三段式业务化汇报】：\n" +
                "   - ① 仓库总体概况：直接告诉客户当前挂载的代码库名称、分支、运行状态是否正常；\n" +
                "   - ② 解读：说明最新一次提交是谁更新的、用通俗的语言解释这次改动了什么业务功能，不要只抄写英文提交信息；\n" +
                "   - ③ 风险诊断与建议：若发现潜在风险（如涉及带配置名称的文件），用大白话解释具体风险点（如'需确认是否包含真实密码，若是示例则安全'），并给出 1~2 个快捷指引；\n" +
                "   - ④ 在回复最下方附上当前时间（当前时间：" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "）；\n" +
                "让客户与高管 10 秒看清全貌，放心决策。";
        } else {
            rawSystemPrompt = (rawSystemPrompt != null ? rawSystemPrompt : "") + "\n\n" +
                "【通用交互回答规范】：\n" +
                "1. 严格使用清晰的 Markdown 换行排版；\n" +
                "2. 语言亲切自然、通俗温暖；根据用户意图直接回答，若用户表达疲劳、困倦或日常闲聊，给予贴心关怀与放松小建议，严禁套用代码仓库巡检或技术报告模板。";
        }

        // 动态加载 Tab 3 中的排查准则与技能白名单 (Tab 3 Skills/Rules Injection)
        List<String> enabledSkillsList = new ArrayList<>();
        if (matchedWorkflow != null && matchedWorkflow.getDslJson() != null && !matchedWorkflow.getDslJson().isBlank()) {
            try {
                com.fasterxml.jackson.databind.JsonNode rootDsl = objectMapper.readTree(matchedWorkflow.getDslJson());
                if (rootDsl.has("skills") && rootDsl.get("skills").isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode skillNode : rootDsl.get("skills")) {
                        boolean isEnabled = !skillNode.has("enabled") || skillNode.get("enabled").asBoolean(true);
                        if (isEnabled) {
                            String skillName = skillNode.path("name").asText("排查规则");
                            enabledSkillsList.add(skillName);
                            String systemInst = skillNode.path("systemInstruction").asText("");
                            if (!systemInst.isBlank()) {
                                rawSystemPrompt = (rawSystemPrompt != null ? rawSystemPrompt : "") + "\n\n【专项技能排查准则 · " + skillName + "】：\n" + systemInst;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("解析 workflow dsl skills 失败", e);
            }
        }
        final List<String> finalEnabledSkills = Collections.unmodifiableList(enabledSkillsList);

        final com.ownsudo.entity.WorkflowDefinition finalMatchedWf = matchedWorkflow;
        final String finalMatchedReason = matchedIntentReason;

        final com.ownsudo.entity.GitRepositoryEntity finalBoundRepo = boundRepo;
        final String activeAgentName = agentName;
        final String effectiveSystemPrompt = rawSystemPrompt;
        final boolean finalIsGitOrCodeTask = isGitOrCodeTask;
        AtomicBoolean isClientConnected = new AtomicBoolean(true);

        emitter.onCompletion(() -> isClientConnected.set(false));
        emitter.onTimeout(() -> isClientConnected.set(false));
        emitter.onError(e -> isClientConnected.set(false));

        executor.submit(() -> {
            AtomicReference<String> activeModelStepId = new AtomicReference<>(null);
            Map<Integer, Object> traceStepsMap = new ConcurrentHashMap<>();
            List<Map<String, Object>> toolCallsList = new CopyOnWriteArrayList<>();
            AtomicReference<Object> approvalTicketRef = new AtomicReference<>(null);
            try {
                // ── 风控防线 A: 全站每日预算上限拦截 (每天最多 ¥1.00，约 10 万 Tokens) ──
                if (isDailyBudgetExceeded) {
                    sendTraceStep(emitter, isClientConnected, traceStepsMap, 1, "CLIENT_REQUEST", "人机交互层 · 访客安全验证", "DONE", Map.of("clientIp", clientIp, "status", "全站演示预算已满"));
                    sendTraceStep(emitter, isClientConnected, traceStepsMap, 2, "INTENT_ROUTER", "任务调度中枢 · 算力风控熔断", "DONE", Map.of("intentLabel", "全站每日算力预算熔断保护", "slotTags", List.of("触发每日 ¥1.00 预算上限", "已自动切断云端调用")));
                    sendTraceStep(emitter, isClientConnected, traceStepsMap, 3, "SAFETY_BASELINE", "规程防线 · 每日成本熔断规则生效", "DONE", Map.of("rule", "RULE-FINOPS-001 每日预算上限 ¥1.00"));

                    String notice = "> 🛡️ **【今日全站体验算力预算已达上限（¥1.00 / 天）】**\n\n"
                            + "今日全站免费演示算力预算（¥1.00 额度，约 10 万 Tokens）已全额消耗完毕。系统已自动启动安全熔断防线，保护云资源账户安全。\n\n"
                            + "**如何继续体验：**\n"
                            + "1. **明日自动重置**：每日 00:00 演示额度将自动恢复满额；\n"
                            + "2. **配置私有 API Key**：前往左侧导航栏 **「设置 / 治理」** 填入您自己的 API Key（阿里云、DeepSeek 等），不受全站演示预算限制；\n"
                            + "3. **切换本地 Ollama**：在「设置」中切换为「本地 Ollama」，零成本无限畅聊。\n";

                    emitter.send(SseEmitter.event().name("delta").data(notice));
                    sendTraceStep(emitter, isClientConnected, traceStepsMap, 6, "STREAM_DELIVERY", "把关交活 · 预算熔断拦截交付", "DONE", Map.of("result", "预算防护生效，已阻断云端扣费"));
                    emitter.send(SseEmitter.event().name("finish").data("{\"tokens\":0,\"duration\":\"0.1s\"}"));
                    emitter.complete();
                    return;
                }

                // ── 风控防线 B: 单 IP 每日限额拦截 (每个 IP 每天最多提问 5 轮) ──
                if (isIpLimitExceeded) {
                    sendTraceStep(emitter, isClientConnected, traceStepsMap, 1, "CLIENT_REQUEST", "人机交互层 · 访客身份识别", "DONE", Map.of("clientIp", clientIp, "status", "单IP体验频次达标"));
                    sendTraceStep(emitter, isClientConnected, traceStepsMap, 2, "INTENT_ROUTER", "任务调度中枢 · 单IP额度风控", "DONE", Map.of("intentLabel", "单IP每日 5 轮限额保护", "slotTags", List.of("访客IP: " + clientIp, "已达 5/5 次上限")));
                    sendTraceStep(emitter, isClientConnected, traceStepsMap, 3, "SAFETY_BASELINE", "规程防线 · 防恶意刷量规则生效", "DONE", Map.of("rule", "RULE-SEC-RATE-005 单 IP 每日上限 5 轮"));

                    String notice = "> ⚠️ **【体验额度已达上限（5/5 轮）】**\n\n"
                            + "您所在的 IP（`" + clientIp + "`）今日免费体验提问已达 **5 轮** 上限。\n\n"
                            + "**防刷保护说明：**\n"
                            + "本平台为企业级演示系统，为避免公共演示接口被恶意高频刷取，限制单 IP 每日免费体验 5 轮。\n\n"
                            + "**如何继续使用：**\n"
                            + "1. **明日自动重置**：明日 00:00 您所在 IP 的 5 次体验机会将自动恢复；\n"
                            + "2. **配置专属 API Key**：前往左侧导航栏 **「设置 / 治理」** 填入您自己的 API Key（通义千问 / DeepSeek / 硅基流动等），畅享无限制对话；\n"
                            + "3. **切换本地模型**：在「设置」中切换为「本地 Ollama」，完全免费且无任何次数限制。\n";

                    emitter.send(SseEmitter.event().name("delta").data(notice));
                    sendTraceStep(emitter, isClientConnected, traceStepsMap, 6, "STREAM_DELIVERY", "把关交活 · 频次风控拦截交付", "DONE", Map.of("result", "单IP防刷拦截生效，保护算力资源"));
                    emitter.send(SseEmitter.event().name("finish").data("{\"tokens\":0,\"duration\":\"0.1s\"}"));
                    emitter.complete();
                    return;
                }

                // 正常放行：累加该 IP 本日的访问轮数
                dailyIpUsageMap.computeIfAbsent(ipKey, k -> new java.util.concurrent.atomic.AtomicInteger(0)).incrementAndGet();

                ChatMessageEntity userMsg = new ChatMessageEntity();
                userMsg.setId("msg-" + UUID.randomUUID().toString().substring(0, 8));
                userMsg.setSessionId(sessionId);
                userMsg.setRole("user");
                userMsg.setContent(messageText);
                userMsg.setTokens(0);
                messageRepository.save(userMsg);

                Map<String, Object> runIdentity = chatRunRecorder.start(
                        sessionId, agentId, activeAgentName,
                        rawModel != null ? rawModel : llmClientService.getConfig("LLM_MODEL", "configured-model"),
                        llmClientService.getConfig("LLM_PROVIDER", "configured-provider"), messageText);
                try {
                    emitter.send(SseEmitter.event().name("run").data(objectMapper.writeValueAsString(runIdentity)));
                } catch (Exception ignored) {}

                final StringBuilder reasoningBuffer = new StringBuilder();

                Boolean enableRag = (Boolean) req.getOrDefault("enableRAG", true);
                Boolean enableThinking = (Boolean) req.getOrDefault("enableThinking", true);
                Boolean enableWebSearch = (Boolean) req.getOrDefault("enableWebSearch", false);

                String promptHashKey = exactHashCacheService.computeSha256(exactHashCacheService.normalizePrompt(messageText));

                // 实时下发步骤 1: 人机交互层 (UACOS) · 前端输入采集与上下文打包
                sendTraceStep(emitter, isClientConnected, traceStepsMap, 1, "CLIENT_REQUEST", "人机交互层 (UACOS) · 前端输入采集与上下文打包", "DONE", Map.of(
                    "model", rawModel != null ? rawModel : "qwen-max",
                    "hashKey", promptHashKey,
                    "sessionId", sessionId,
                    "enableRAG", enableRag,
                    "enableThinking", enableThinking,
                    "enableWebSearch", enableWebSearch,
                    "userPrompt", messageText
                ));

                // 实时下发步骤 2: 任务层 (Task Layer) · 意图智能研判与业务流装载
                String intentLabel;
                String intentCategory;
                String extractedEntitiesDesc;
                List<String> slotTags = new ArrayList<>();

                if (finalIsGitOrCodeTask) {
                    intentLabel = "代码工程实时巡检与提交审计";
                    intentCategory = "工程运维/代码审计";
                    String rName = finalBoundRepo != null ? finalBoundRepo.getName() : "builder";
                    String rBranch = finalBoundRepo != null ? finalBoundRepo.getBranch() : "main";
                    extractedEntitiesDesc = "锁定代码仓库：" + rName + " (分支: " + rBranch + ") · 范围: 仓库运行状态与最新改动";
                    slotTags.add("代码库: " + rName);
                    slotTags.add("分支: " + rBranch);
                } else {
                    String lower = messageText != null ? messageText.toLowerCase(Locale.ROOT) : "";
                    if (lower.contains("困") || lower.contains("累") || lower.contains("歇") || lower.contains("睡") || lower.contains("难受") || lower.contains("休息")) {
                        intentLabel = "身心状态表达与关怀问候";
                        intentCategory = "日常关怀/情绪感知";
                        extractedEntitiesDesc = "识别用户身心状态：疲倦/困倦 · 保持当前会话上下文 · 纯文本生成模式";
                        slotTags.add("用户状态: 困倦疲乏");
                        slotTags.add("免挂载数据源");
                    } else if (lower.contains("你好") || lower.contains("早") || lower.contains("嗨") || lower.contains("hello") || lower.contains("hi")) {
                        intentLabel = "问候礼貌交互";
                        intentCategory = "日常问候";
                        extractedEntitiesDesc = "识别日常问候礼仪 · 保持当前会话上下文 · 纯文本生成模式";
                        slotTags.add("问候交互");
                        slotTags.add("免挂载数据源");
                    } else {
                        intentLabel = "通用自然语言理解与知识解答";
                        intentCategory = "通用问答";
                        extractedEntitiesDesc = "识别通用问答场景 · 纯认知生成 · 无外部代码工程依赖";
                        slotTags.add("通用文本交互");
                        slotTags.add("免挂载数据源");
                    }
                }

                Map<String, Object> routerDetails = new LinkedHashMap<>();
                routerDetails.put("matchedWorkflowId", finalMatchedWf != null ? finalMatchedWf.getId() : null);
                routerDetails.put("matchedWorkflowName", finalMatchedWf != null ? finalMatchedWf.getName() : null);

                routerDetails.put("matchedReason", finalMatchedReason);
                routerDetails.put("autoDispatched", true);
                routerDetails.put("intentLabel", intentLabel);
                routerDetails.put("intentCategory", intentCategory);
                routerDetails.put("extractedEntities", extractedEntitiesDesc);
                routerDetails.put("slotTags", slotTags);
                routerDetails.put("workflowCategory", finalMatchedWf != null ? finalMatchedWf.getCategory() : null);
                routerDetails.put("mountedRepo", finalBoundRepo != null ? finalBoundRepo.getName() + " (" + finalBoundRepo.getBranch() + ")" : "无关联外部数据源");

                sendTraceStep(emitter, isClientConnected, traceStepsMap, 2, "INTENT_ROUTER", "任务层 (Task Layer) · 意图智能研判与业务流装载", "DONE", routerDetails);

                if (isClientConnected.get()) {
                    try {
                        String rText = finalIsGitOrCodeTask
                                ? "用户发起了代码巡检，我来先看一下当前本地工作区的代码状态与最新提交记录。\n"
                                : "理解了用户的问题，我来理清思路，用通俗易懂的大白话直接给出解答。\n";
                        reasoningBuffer.append(rText);
                        emitter.send(SseEmitter.event().name("reasoning").data(rText));
                    } catch (IOException ignored) {}
                }

                // 1.5. 精确哈希降本缓存前置拦截 (Exact Hash Cache Check - 0 Token 极速返回)
                // 强门禁：凡涉及代码工程、Git 仓库、现场状态巡检等实时动态任务，严禁走静态缓存，确保受控沙箱现场真实执行
                String lowerMessage = messageText != null ? messageText.toLowerCase(Locale.ROOT) : "";
                boolean isDynamicStatusQuery = lowerMessage.contains("git") ||
                        lowerMessage.contains("commit") ||
                        lowerMessage.contains("状态") ||
                        lowerMessage.contains("排查") ||
                        lowerMessage.contains("最新") ||
                        lowerMessage.contains("分支") ||
                        lowerMessage.contains("diff") ||
                        lowerMessage.contains("status") ||
                        lowerMessage.contains("log");

                com.ownsudo.entity.PromptHashCacheEntity cached = isDynamicStatusQuery ? null : exactHashCacheService.lookupAndHit(messageText);

                if (cached != null) {
                    long hitDurationMs = Math.max(1, System.currentTimeMillis() - startTime);
                    sendTraceStep(emitter, isClientConnected, traceStepsMap, 2, "FINOPS_CACHE", "命中历史问答缓存", "HIT", Map.of(
                        "hashKey", promptHashKey,
                        "cached", true,
                        "durationMs", hitDurationMs,
                        "savedTokens", cached.getOriginalTokens() != null ? cached.getOriginalTokens() : 0
                    ));

                    String durationStr = String.format(Locale.US, "%.2fs", hitDurationMs / 1000.0);

                    // A. 若原始记录包含知识库切片引用，下发 citations
                    if (cached.getCitationsJson() != null && !cached.getCitationsJson().isBlank()) {
                        try {
                            emitter.send(SseEmitter.event().name("citations").data(cached.getCitationsJson()));
                        } catch (Exception ignored) {}
                    }

                    // B. 推送缓存命中标记到 reasoning
                    String cacheBanner = "> 与历史相同问题，已直接返回答案（未再调用模型）。\n\n";
                    try {
                        emitter.send(SseEmitter.event().name("reasoning").data(cacheBanner + (cached.getReasoning() != null ? cached.getReasoning() : "")));
                    } catch (Exception ignored) {}

                    // C. 快速推流输出正文
                    try {
                        emitter.send(SseEmitter.event().name("delta").data(cached.getContent()));
                    } catch (Exception ignored) {}

                    // D. 持久化助手消息
                    ChatMessageEntity asstMsg = new ChatMessageEntity();
                    asstMsg.setId("msg-" + UUID.randomUUID().toString().substring(0, 8));
                    asstMsg.setSessionId(sessionId);
                    asstMsg.setRole("assistant");
                    asstMsg.setContent(cached.getContent());
                    asstMsg.setReasoning(cached.getReasoning());
                    asstMsg.setTokens(0);
                    asstMsg.setDuration(durationStr);
                    asstMsg.setIsCached(true);
                    asstMsg.setCitationsJson(cached.getCitationsJson());
                    try {
                        asstMsg.setTraceStepsJson(objectMapper.writeValueAsString(traceStepsMap));
                        Map<String, Object> traceSummary = new LinkedHashMap<>();
                        traceSummary.putAll(chatRunRecorder.currentIdentity());
                        traceSummary.put("cached", true);
                        traceSummary.put("totalMs", hitDurationMs);
                        traceSummary.put("tokens", 0);
                        asstMsg.setTraceJson(objectMapper.writeValueAsString(traceSummary));
                    } catch (Exception ignored) {}
                    messageRepository.save(asstMsg);

                    // E. 写入真实审计对账日志
                    AuditLogEntity audit = new AuditLogEntity();
                    audit.setId("log-" + UUID.randomUUID().toString().substring(0, 8));
                    audit.setTime(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                    audit.setAgentName(activeAgentName);
                    audit.setEventType("CACHE_HIT");
                    audit.setModel("exact-hash-cache");
                    audit.setDuration(durationStr);
                    audit.setTokensIn(0);
                    audit.setTokensOut(0);
                    audit.setIsRealUsage(true);
                    audit.setStatus("SUCCESS");
                    auditLogRepository.save(audit);

                    // F. 完成事件下发，携带真实 Trace 数据
                    chatRunRecorder.complete(0, 0, false, "COMPLETED");
                    Map<String, Object> traceMap = new LinkedHashMap<>();
                    traceMap.put("totalMs", hitDurationMs);
                    traceMap.put("ragMs", 0);
                    traceMap.put("ttftMs", hitDurationMs);
                    traceMap.put("streamMs", 0);
                    traceMap.put("cached", true);
                    traceMap.put("hitType", "EXACT_HASH_HIT");
                    traceMap.put("savedTokens", cached.getOriginalTokens() != null ? cached.getOriginalTokens() : 0);
                    traceMap.put("savedMs", cached.getOriginalDurationMs() != null ? cached.getOriginalDurationMs() : 1000);
                    traceMap.put("hashKey", cached.getHashKey());

                    Map<String, Object> finishPayload = new LinkedHashMap<>();
                    finishPayload.put("tokens", 0);
                    finishPayload.put("duration", durationStr);
                    finishPayload.put("cached", true);
                    finishPayload.put("savedTokens", cached.getOriginalTokens() != null ? cached.getOriginalTokens() : 0);
                    finishPayload.put("trace", traceMap);

                    // 实时下发步骤 6 完成
                    sendTraceStep(emitter, isClientConnected, traceStepsMap, 6, "LLM_RESPONSE", "结果组装与交付", "DONE", Map.of(
                        "totalMs", hitDurationMs,
                        "tokens", 0,
                        "duration", durationStr
                    ));

                    try {
                        emitter.send(SseEmitter.event().name("finish").data(objectMapper.writeValueAsString(finishPayload)));
                        emitter.complete();
                    } catch (Exception ignored) {}
                    return;
                }

                String currentPrompt = effectiveSystemPrompt != null ? effectiveSystemPrompt : "";

                // 2. 长期记忆召回 (根据当前提问相关性从 agent_memory 跨会话召回，无交集则不注入)
                List<String> rememberedFacts = agentMemoryService.recallRelevantFacts(sessionId, messageText);
                if (!rememberedFacts.isEmpty()) {
                    currentPrompt = agentMemoryService.buildMemoryInjectedPrompt(currentPrompt, rememberedFacts);
                }

                // 3. 真实 RAG 检索进回答并按 >= 0.65 硬阈值过滤防幻觉
                List<Map<String, Object>> citationsList = new ArrayList<>();
                long ragStartTime = System.currentTimeMillis();
                long ragDurationMs = 0;
                HybridRagService.SearchResult searchResult = null;

                if (Boolean.TRUE.equals(enableRag)) {
                    sendTraceStep(emitter, isClientConnected, traceStepsMap, 3, "HYBRID_RAG", "企业知识库多路召回", "RUNNING", Map.of("query", messageText));
                    searchResult = hybridRagService.hybridSearchWithStats(messageText, 5);
                    ragDurationMs = searchResult.getCostMs();
                    List<HybridRagService.RagChunk> highConfidenceChunks = searchResult.getChunks();

                    if (!highConfidenceChunks.isEmpty()) {
                        StringBuilder citations = new StringBuilder();
                        int citationIdx = 1;
                        for (HybridRagService.RagChunk chunk : highConfidenceChunks) {
                            citations.append("\n【参考资料 [").append(citationIdx).append("]：").append(chunk.getProjectName())
                                     .append(" (得分: ").append(chunk.getDenseScore())
                                     .append(", BM25: ").append(chunk.getSparseScore()).append(")】\n")
                                     .append(chunk.getText()).append("\n");

                            Map<String, Object> cItem = new LinkedHashMap<>();
                            cItem.put("index", citationIdx);
                            cItem.put("chunkId", chunk.getChunkId());
                            cItem.put("docTitle", chunk.getProjectName());
                            cItem.put("section", chunk.getSection());
                            cItem.put("snippet", chunk.getText().length() > 80 ? chunk.getText().substring(0, 80) + "..." : chunk.getText());
                            cItem.put("score", chunk.getDenseScore());
                            cItem.put("bm25Score", chunk.getSparseScore());
                            cItem.put("rrfScore", chunk.getRrfScore());
                            cItem.put("matchType", chunk.getVectorMode());
                            citationsList.add(cItem);
                            citationIdx++;
                        }

                        // 通过 SSE 发送引用溯源事件
                        if (isClientConnected.get() && !citationsList.isEmpty()) {
                            try {
                                Map<String, Object> citationsPayload = new LinkedHashMap<>();
                                citationsPayload.put("items", citationsList);
                                citationsPayload.put("totalDocs", searchResult.getTotalDocs());
                                citationsPayload.put("bm25Candidates", searchResult.getBm25Candidates());
                                citationsPayload.put("noiseFiltered", searchResult.getNoiseFiltered());
                                citationsPayload.put("validCount", searchResult.getValidCount());
                                citationsPayload.put("mode", searchResult.getMode());
                                emitter.send(SseEmitter.event().name("citations").data(objectMapper.writeValueAsString(citationsPayload)));
                            } catch (Exception ignored) {}
                        }

                        currentPrompt = currentPrompt.isBlank()
                                ? "你是企业级智能中台助手。请结合以下检索到的知识库真实材料作答。在引述事实或规范时必须在正文后标明对应角标，形如 [1] 或 [2]，严禁脱离材料随意编造：\n" + citations
                                : currentPrompt + "\n\n【企业知识库关联上下文（必须在正文事实后标明对应 [1] 或 [2] 引用角标）】：\n" + citations;
                    } else {
                        // 未命中高置信度切片：降级为通用常识回答，严防模型幻觉
                        String fallbackNotice = "\n\n【知识库检索提示】：知识库未找到足够相关内容，以下回答未引用内部制度。";
                        currentPrompt = currentPrompt.isBlank() ? "你是企业级智能中台助手。" + fallbackNotice : currentPrompt + fallbackNotice;
                    }

                    Map<String, Object> ragDetails = new LinkedHashMap<>();
                    ragDetails.put("ragMs", ragDurationMs);
                    ragDetails.put("totalDocs", searchResult.getTotalDocs());
                    ragDetails.put("bm25Candidates", searchResult.getBm25Candidates());
                    ragDetails.put("noiseFiltered", searchResult.getNoiseFiltered());
                    ragDetails.put("validCount", searchResult.getValidCount());
                    ragDetails.put("isRealVector", searchResult.isRealVector());
                    ragDetails.put("mode", searchResult.getMode());
                    Map<String, Object> hydrationDetails = new LinkedHashMap<>(ragDetails);
                    if (finalBoundRepo != null) hydrationDetails.put("injectedVariables", "repo_name=" + finalBoundRepo.getName() + ", branch=" + finalBoundRepo.getBranch());
                    hydrationDetails.put("promptLength", currentPrompt != null ? currentPrompt.length() : 0);
                    hydrationDetails.put("ragCostMs", ragDurationMs);
                    hydrationDetails.put("activeSkills", finalEnabledSkills);
                    hydrationDetails.put("skillCount", finalEnabledSkills.size());
                    sendTraceStep(emitter, isClientConnected, traceStepsMap, 3, "CONTEXT_HYDRATION", "执行引擎层 (Engine Layer) · 参数动态注入与提示词工程", "DONE", hydrationDetails);
                } else {
                    Map<String, Object> hydrationDetails = new LinkedHashMap<>();
                    hydrationDetails.put("injectedVariables", finalBoundRepo != null ? "repo_name=" + finalBoundRepo.getName() + ", branch=" + finalBoundRepo.getBranch() : "通用系统环境");
                    hydrationDetails.put("promptLength", currentPrompt != null ? currentPrompt.length() : 0);

                    hydrationDetails.put("activeSkills", finalEnabledSkills);
                    hydrationDetails.put("skillCount", finalEnabledSkills.size());
                    sendTraceStep(emitter, isClientConnected, traceStepsMap, 3, "CONTEXT_HYDRATION", "执行引擎层 (Engine Layer) · 参数动态注入与提示词工程", "DONE", hydrationDetails);
                }

                if (isClientConnected.get()) {
                    try {
                        String rText;
                        if (finalIsGitOrCodeTask) {
                            String rName = finalBoundRepo != null ? finalBoundRepo.getName() : "builder";
                            String rBranch = finalBoundRepo != null ? finalBoundRepo.getBranch() : "main";
                            rText = "确认目标代码库，目前工作区在 " + rName + "（" + rBranch + " 分支）。\n";
                        } else {
                            rText = "梳理回答的核心要点，组织通顺清晰的语言。\n";
                        }
                        reasoningBuffer.append(rText);
                        emitter.send(SseEmitter.event().name("reasoning").data(rText));
                    } catch (IOException ignored) {}
                }

                // 4. 上下文滑动窗口：严格截断最近 3 轮 (最多 6 条历史消息：3 user + 3 assistant)
                List<ChatMessageEntity> allHistory = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
                int maxHistory = 6;
                List<ChatMessageEntity> history = allHistory.size() > maxHistory
                        ? allHistory.subList(allHistory.size() - maxHistory, allHistory.size())
                        : allHistory;

                List<Map<String, Object>> messages = new ArrayList<>();
                if (!currentPrompt.isBlank()) {
                    String formattingGuide;
                    if (finalIsGitOrCodeTask) {
                        formattingGuide = "\n\n" +
                            "【🚨 极重要格式与排版铁律（面向企业高管与客户）】:\n" +
                            "1. 【严格换行】：严禁将所有文字挤在单行！标题前必须空一行，段落与段落之间必须空一行（双换行），列表项每行一条（- 开头）！\n" +
                            "2. 【严禁生涩黑话】：绝对不要输出底层机器编号（如 repo-0aac5a67）、生僻参数（如 commit_sha=xxx、filePath）或未格式化的机器字符串；\n" +
                            "3. 【业务化三段式汇报格式模板】：\n\n" +
                            "### 📊 代码仓库运行现状\n" +
                            "- **代码仓库**：`项目名称`（主分支：`分支名`）\n" +
                            "- **运行状态**：正常在线，已接入受控沙箱实时监控\n\n" +
                            "### 📝 解读\n" +
                            "- **提交作者**：`作者名`\n" +
                            "- **改动内容**：用通俗易懂的中文大白话说明这次到底更新了什么业务功能，绝不直接照抄生硬英文\n\n" +
                            "### 🩺 安全与健康诊断\n" +
                            "- **诊断结论**：说明当前代码库是否安全、是否存在潜在隐患\n" +
                            "- **排查建议**：若有风险（如配置文件命名），清晰说明原因与防范措施（如确认未含真实密码即可放行）\n\n" +
                            "### 💡 下一步指引\n" +
                            "给业务方 1~2 条简单快捷的后续操作建议（如：可随时指示“查看最近 5 条更新记录”或“查看具体代码改动”）。\n\n" +
                            "⏱️ **当前时间**：" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "\n\n" +
                            "请务必按照上述结构与 Markdown 空行规范输出，并在回复末尾附上上述当前时间！";
                    } else {
                        formattingGuide = "\n\n" +
                            "【排版与对话规范】:\n" +
                            "1. 严格使用清晰的 Markdown 排版，段落之间保持空行；\n" +
                            "2. 根据用户提问自然作答，亲切友好、温暖贴心；若用户表达疲劳、困倦或闲聊，给予贴心关怀与放松建议，切勿套用代码仓库报告模板。";
                    }

                    messages.add(Map.of("role", "system", "content", currentPrompt + formattingGuide));
                }
                for (ChatMessageEntity h : history) {
                    if ("user".equals(h.getRole()) || "assistant".equals(h.getRole())) {
                        messages.add(Map.of("role", h.getRole(), "content", h.getContent() != null ? h.getContent() : ""));
                    }
                }

                // 5. 真实工具调用闭环 (大模型驱动，彻底杜绝关键词旁路与假调用)
                // 实时通知前端进入“03. 动手排查”阶段 (沙箱工具调度与专家大脑推演)
                if (finalIsGitOrCodeTask) {
                    sendTraceStep(emitter, isClientConnected, traceStepsMap, 4, "LLM_REASONING", "专家大脑协同作业与现场排查", "RUNNING", Map.of("mode", "沙箱受控执行与认知推演"));
                } else {
                    sendTraceStep(emitter, isClientConnected, traceStepsMap, 4, "LLM_REASONING", "专家大脑认知推演与回复生成", "RUNNING", Map.of("mode", "通用对话认知理解与共情构思"));
                }

                int currentDepth = 1;
                List<Map<String, Object>> toolDefs = toolExecutor.getToolDefinitions(allowedToolNames.get()).stream()
                        .filter(definition -> {
                            Object function = definition.get("function");
                            return function instanceof Map<?, ?> details
                                    && !disabledToolNames.contains(String.valueOf(details.get("name")));
                        }).toList();
                int totalToolsExecuted = 0;
                boolean hasPendingApproval = false;
                Map<String, Object> pendingApprovalData = null;

                while (currentDepth <= 3 && isClientConnected.get()) {
                    try {
                        String modelStepId = "model-call-round-" + currentDepth;
                        activeModelStepId.set(modelStepId);
                        Map<String, Object> modelRequestDetail = new LinkedHashMap<>();
                        modelRequestDetail.put("model", rawModel != null ? rawModel : llmClientService.getConfig("LLM_MODEL", "configured-model"));
                        modelRequestDetail.put("provider", llmClientService.getConfig("LLM_PROVIDER", "configured-provider"));
                        modelRequestDetail.put("model_step", currentDepth);
                        modelRequestDetail.put("tool_count", toolDefs.size());
                        if (isExecutionContentCaptureEnabled()) {
                            modelRequestDetail.put("prompt_messages", boundedTraceValue(messages));
                            modelRequestDetail.put("tool_schema", boundedTraceValue(toolDefs));
                        } else {
                            modelRequestDetail.put("content_capture", "disabled");
                        }
                        sendExecutionEvent(emitter, isClientConnected, traceStepsMap, modelStepId, 40 + currentDepth,
                                "llm", "running", "模型请求 · 第 " + currentDepth + " 轮",
                                "正在向模型发送当前上下文与可用工具定义", modelRequestDetail);

                        com.fasterxml.jackson.databind.JsonNode compResponse =
                                llmClientService.callChatCompletionsWithTools(rawModel, temperature, messages, toolDefs, isClientConnected);

                        if (compResponse == null || !compResponse.has("choices") || compResponse.get("choices").size() == 0) {
                            Map<String, Object> emptyResponseDetail = new LinkedHashMap<>(modelRequestDetail);
                            emptyResponseDetail.put("error", "模型未返回有效 choices");
                            sendExecutionEvent(emitter, isClientConnected, traceStepsMap, modelStepId, 40 + currentDepth,
                                    "llm", "failed", "模型响应为空 · 第 " + currentDepth + " 轮",
                                    "Provider 没有返回可用的模型消息", emptyResponseDetail);
                            activeModelStepId.compareAndSet(modelStepId, null);
                        }

                        if (compResponse != null && compResponse.has("choices") && compResponse.get("choices").size() > 0) {
                            com.fasterxml.jackson.databind.JsonNode messageNode = compResponse.get("choices").get(0).path("message");
                            Map<String, Object> modelResponseDetail = new LinkedHashMap<>(modelRequestDetail);
                            if (isExecutionContentCaptureEnabled()) {
                                modelResponseDetail.put("model_response", boundedTraceValue(objectMapper.convertValue(messageNode, Object.class)));
                            } else {
                                modelResponseDetail.put("content_capture", "disabled");
                            }
                            if (compResponse.has("usage")) modelResponseDetail.put("usage", objectMapper.convertValue(compResponse.get("usage"), Object.class));
                            if (compResponse.has("model")) modelResponseDetail.put("actual_model", compResponse.path("model").asText());
                            sendExecutionEvent(emitter, isClientConnected, traceStepsMap, modelStepId, 40 + currentDepth,
                                    "llm", "done", "模型响应 · 第 " + currentDepth + " 轮",
                                    messageNode.has("tool_calls") ? "模型返回了工具调用请求" : "模型返回了文本内容",
                                    modelResponseDetail);
                            activeModelStepId.compareAndSet(modelStepId, null);

                            if (messageNode.has("tool_calls") && messageNode.get("tool_calls").isArray() && messageNode.get("tool_calls").size() > 0) {
                                com.fasterxml.jackson.databind.JsonNode toolCallsArray = messageNode.get("tool_calls");
                                totalToolsExecuted += toolCallsArray.size();

                                // 构造包含全部 tool_calls 的 assistant 消息
                                List<Map<String, Object>> assistantToolCallsList = new ArrayList<>();
                                for (com.fasterxml.jackson.databind.JsonNode toolCallNode : toolCallsArray) {
                                    String callId = toolCallNode.path("id").asText("call-" + UUID.randomUUID().toString().substring(0, 6));
                                    String functionName = toolCallNode.path("function").path("name").asText();
                                    String functionArgs = toolCallNode.path("function").path("arguments").asText("{}");
                                    assistantToolCallsList.add(Map.of(
                                        "id", callId,
                                        "type", "function",
                                        "function", Map.of("name", functionName, "arguments", functionArgs)
                                    ));
                                }

                                Map<String, Object> assistantToolMsg = new LinkedHashMap<>();
                                assistantToolMsg.put("role", "assistant");
                                assistantToolMsg.put("content", null);
                                assistantToolMsg.put("tool_calls", assistantToolCallsList);
                                messages.add(assistantToolMsg);

                                // 遍历执行每一个 tool_call，并独立审计与返回 role: tool
                                for (int i = 0; i < toolCallsArray.size(); i++) {
                                    com.fasterxml.jackson.databind.JsonNode toolCallNode = toolCallsArray.get(i);
                                    Map<String, Object> callMeta = assistantToolCallsList.get(i);
                                    String callId = (String) callMeta.get("id");
                                    String functionName = toolCallNode.path("function").path("name").asText();
                                    String functionArgs = toolCallNode.path("function").path("arguments").asText("{}");

                                    if (isClientConnected.get()) {
                                        try {
                                            String rText;
                                            if (functionName.contains("status")) {
                                                rText = "执行 git status 查看一下，看看哪些文件刚刚被改动过、有没有未暂存的代码……\n";
                                            } else if (functionName.contains("log")) {
                                                rText = "查一下最近的 git log 提交历史，看看最新的一次 commit 改了什么、是谁提交的……\n";
                                            } else {
                                                rText = "调取现场数据，执行 " + functionName + " 排查具体变动……\n";
                                            }
                                            reasoningBuffer.append(rText);
                                            emitter.send(SseEmitter.event().name("reasoning").data(rText));
                                        } catch (IOException ignored) {}
                                    }

                                    Map<String, Object> toolStartDetail = new LinkedHashMap<>();
                                    toolStartDetail.put("functionName", functionName);
                                    toolStartDetail.put("toolCallId", callId);
                                    toolStartDetail.put("modelStep", currentDepth);
                                    toolStartDetail.put("tool_arguments", boundedTraceValue(parseJsonOrText(functionArgs)));
                                    Map<String, Object> toolStartPayload = new LinkedHashMap<>();
                                    toolStartPayload.put("stepId", callId);
                                    toolStartPayload.put("seq", 5);
                                    toolStartPayload.put("phase", "skill");
                                    toolStartPayload.put("status", "running");
                                    toolStartPayload.put("leftTitle", "正在执行工具: " + functionName);
                                    toolStartPayload.put("leftSummary", "模型已发起工具调用，执行端正在处理");
                                    toolStartPayload.put("title", "正在执行工具: " + functionName);
                                    toolStartPayload.put("rightTitle", functionName);
                                    toolStartPayload.put("rightSubtitle", "工具调用已接收，正在执行");
                                    toolStartPayload.put("rightDetail", toolStartDetail);
                                    toolStartPayload.put("timestamp", System.currentTimeMillis());
                                    if (isClientConnected.get()) {
                                        try {
                                            emitter.send(SseEmitter.event().name("agent_step").data(objectMapper.writeValueAsString(toolStartPayload)));
                                        } catch (IOException ignored) {}
                                    }

                                    chatRunRecorder.recordToolStart(callId, functionName, parseJsonOrText(functionArgs) instanceof Map<?, ?> argsMap ? (Map<String, Object>) argsMap : Map.of("raw", functionArgs));
                                    long toolStart = System.currentTimeMillis();
                                    ToolExecutor.ToolExecutionResult toolResult;
                                    try {
                                        if (allowedToolNames.get() != null && !allowedToolNames.get().contains(functionName)) {
                                            throw new SecurityException("该 Agent 未绑定工具：" + functionName);
                                        }
                                        if (disabledToolNames.contains(functionName)) {
                                            throw new SecurityException("工具已在工具管理中停用：" + functionName);
                                        }
                                        toolResult = toolExecutor.executeTool(functionName, functionArgs, currentDepth);
                                    } catch (RuntimeException toolError) {
                                        long failedDuration = System.currentTimeMillis() - toolStart;
                                        chatRunRecorder.recordToolResult(callId, functionName, false, failedDuration, null,
                                                toolError.getMessage() == null ? toolError.getClass().getSimpleName() : toolError.getMessage());
                                        throw toolError;
                                    }
                                    long toolDuration = System.currentTimeMillis() - toolStart;
                                    chatRunRecorder.recordToolResult(callId, functionName, toolResult.isSuccess(), toolDuration, boundedTraceValue(toolResult.isSuccess() ? toolResult.getData() : null), toolResult.isSuccess() ? null : toolResult.getErrorMessage());
                                    String toolDurationStr = toolDuration + "ms";

                                    if (isClientConnected.get()) {
                                        try {
                                            String rText;
                                            if (functionName.contains("status")) {
                                                rText = "工作区状态拿到了，已掌握改动的文件清单。\n";
                                            } else if (functionName.contains("log")) {
                                                rText = "最新提交记录拿到了，改动要点已明确。\n";
                                            } else {
                                                rText = "现场数据已获取完毕。\n";
                                            }
                                            reasoningBuffer.append(rText);
                                            emitter.send(SseEmitter.event().name("reasoning").data(rText));
                                        } catch (IOException ignored) {}
                                    }

                                    // 全链路审计入库：记录工具名、参数 JSON、耗时与状态
                                    AuditLogEntity toolAudit = new AuditLogEntity();
                                    toolAudit.setId("log-" + UUID.randomUUID().toString().substring(0, 8));
                                    toolAudit.setTime(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                                    toolAudit.setAgentName(activeAgentName);
                                    toolAudit.setEventType("TOOL_CALL");
                                    toolAudit.setToolName(functionName);
                                    toolAudit.setParameters(functionArgs);
                                    toolAudit.setModel(rawModel != null ? rawModel : "tool-engine");
                                    toolAudit.setDuration(toolDurationStr);
                                    toolAudit.setTokensIn(0);
                                    toolAudit.setTokensOut(0);
                                    toolAudit.setStatus(toolResult.isSuccess() ? "SUCCESS" : "ERROR");
                                    toolAudit.setIsRealUsage(false);
                                    auditLogRepository.save(toolAudit);

                                    if (isClientConnected.get()) {
                                        try {
                                            Map<String, Object> toolPayloadMap = new LinkedHashMap<>();
                                            toolPayloadMap.put("tool", functionName);
                                            toolPayloadMap.put("id", callId);
                                            toolPayloadMap.put("toolCallId", callId);
                                            toolPayloadMap.put("modelStep", currentDepth);
                                            toolPayloadMap.put("parameters", boundedTraceValue(parseJsonOrText(functionArgs)));
                                            toolPayloadMap.put("tool_arguments", boundedTraceValue(parseJsonOrText(functionArgs)));
                                            toolPayloadMap.put("status", toolResult.isSuccess() ? "success" : "error");
                                            toolPayloadMap.put("duration", toolDurationStr);
                                            Map<String, Object> savedToolResult = new LinkedHashMap<>();
                                            savedToolResult.put("success", toolResult.isSuccess());
                                            if (toolResult.isSuccess()) {
                                                savedToolResult.put("data", toolResult.getData());
                                            } else {
                                                savedToolResult.put("errorType", toolResult.getErrorType());
                                                savedToolResult.put("message", toolResult.getErrorMessage());
                                                savedToolResult.put("retryable", toolResult.isRetryable());
                                            }
                                            toolPayloadMap.put("tool_result", boundedTraceValue(savedToolResult));
                                            toolPayloadMap.put("result", boundedTraceValue(toolResult.isSuccess() ? toolResult.getData() : toolResult.getErrorMessage()));
                                            toolCallsList.add(toolPayloadMap);
                                            emitter.send(SseEmitter.event().name("tool").data(objectMapper.writeValueAsString(toolPayloadMap)));

                                            // 同步下发高阶 agent_step 事件，驱动双视窗实时同步
                                            Map<String, Object> toolStepPayload = new LinkedHashMap<>();
                                            toolStepPayload.put("seq", 5);
                                            toolStepPayload.put("stepId", callId); // Match the `tool` SSE event identity so the UI merges, rather than duplicates, this call.
                                            toolStepPayload.put("phase", "skill");
                                            toolStepPayload.put("status", toolResult.isSuccess() ? "done" : "failed");
                                            toolStepPayload.put("leftTitle", "技能调度: " + functionName);
                                            toolStepPayload.put("leftSummary", "沙箱执行 " + functionName + " (耗时 " + toolDurationStr + ") · " + (toolResult.isSuccess() ? "成功" : "失败"));
                                            toolStepPayload.put("rightTitle", "执行技能: " + functionName);
                                            toolStepPayload.put("rightSubtitle", "耗时: " + toolDurationStr + " · 状态: " + (toolResult.isSuccess() ? "SUCCESS" : "ERROR"));
                                            Map<String, Object> toolDetail = new LinkedHashMap<>();
                                            toolDetail.put("functionName", functionName);
                                            toolDetail.put("toolCallId", callId);
                                            toolDetail.put("modelStep", currentDepth);
                                            toolDetail.put("tool_arguments", boundedTraceValue(parseJsonOrText(functionArgs)));
                                            Map<String, Object> toolResultTrace = new LinkedHashMap<>();
                                            toolResultTrace.put("success", toolResult.isSuccess());
                                            if (toolResult.isSuccess()) {
                                                toolResultTrace.put("data", toolResult.getData());
                                            } else {
                                                toolResultTrace.put("errorType", toolResult.getErrorType());
                                                toolResultTrace.put("message", toolResult.getErrorMessage());
                                                toolResultTrace.put("retryable", toolResult.isRetryable());
                                            }
                                            toolDetail.put("tool_result", boundedTraceValue(toolResultTrace));
                                            toolDetail.put("parameters", boundedTraceValue(parseJsonOrText(functionArgs)));
                                            toolDetail.put("result", boundedTraceValue(toolResult.isSuccess() ? toolResult.getData() : toolResult.getErrorMessage()));
                                            toolDetail.put("durationMs", toolDuration);
                                            toolStepPayload.put("rightDetail", toolDetail);
                                            toolStepPayload.put("timestamp", System.currentTimeMillis());
                                            // Persist the full tool event alongside model events. The map key is
                                            // only a unique storage key; the event keeps its semantic seq=5.
                                            traceStepsMap.put(1000 + traceStepsMap.size(), toolStepPayload);
                                            emitter.send(SseEmitter.event().name("agent_step").data(objectMapper.writeValueAsString(toolStepPayload)));

                                            // 若触发生产级高危写操作安全拦截，向前端推送 approval_required 事件
                                            if ("PENDING_APPROVAL".equals(toolResult.getErrorType()) && toolResult.getData() != null) {
                                                hasPendingApproval = true;
                                                if (toolResult.getData() instanceof Map) {
                                                    pendingApprovalData = (Map<String, Object>) toolResult.getData();
                                                }
                                                approvalTicketRef.set(toolResult.getData());
                                                emitter.send(SseEmitter.event().name("approval_required").data(objectMapper.writeValueAsString(toolResult.getData())));
                                            }
                                        } catch (Exception ignored) {}
                                    }

                                    // 彻底杜绝字符串拼接 JSON，通过 ObjectMapper 构建规范安全的结构化反馈
                                    Map<String, Object> toolMsg = new LinkedHashMap<>();
                                    toolMsg.put("role", "tool");
                                    toolMsg.put("tool_call_id", callId);
                                    toolMsg.put("name", functionName);

                                    Map<String, Object> toolPayload = new LinkedHashMap<>();
                                    if (toolResult.isSuccess()) {
                                        toolPayload.put("success", true);
                                        toolPayload.put("data", toolResult.getData());
                                    } else {
                                        toolPayload.put("success", false);
                                        toolPayload.put("retryable", toolResult.isRetryable());
                                        toolPayload.put("errorType", toolResult.getErrorType());
                                        toolPayload.put("message", toolResult.getErrorMessage());
                                        if (toolResult.isRetryable()) {
                                            toolPayload.put("instruction", "参数校验未通过，请核对缺失参数或类型规范后纠错重试。");
                                        } else {
                                            toolPayload.put("instruction", "该操作已被系统拒绝或超时熔断，禁止再次调用该工具！请直接向用户说明具体拦截原因并作答。");
                                        }
                                    }
                                    toolMsg.put("content", objectMapper.writeValueAsString(toolPayload));
                                    messages.add(toolMsg);
                                }

                                currentDepth++;
                                continue;
                            }
                        }
                    } catch (Exception e) {
                        Map<String, Object> failedModelDetail = new LinkedHashMap<>();
                        failedModelDetail.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                        failedModelDetail.put("error_type", e.getClass().getSimpleName());
                        failedModelDetail.put("model_step", currentDepth);
                        if (isExecutionContentCaptureEnabled()) {
                            failedModelDetail.put("prompt_messages", boundedTraceValue(messages));
                        } else {
                            failedModelDetail.put("content_capture", "disabled");
                        }
                        String failedStepId = activeModelStepId.getAndSet(null);
                        if (failedStepId != null) {
                            sendExecutionEvent(emitter, isClientConnected, traceStepsMap, failedStepId,
                                    40 + currentDepth, "llm", "failed", "模型请求失败 · 第 " + currentDepth + " 轮",
                                    "模型调用发生异常，已记录真实错误", failedModelDetail);
                        }
                        System.err.println("[ToolLoop] 工具调用循环检查异常: " + e.getMessage());
                    }
                    break;
                }

                // 实时下发步骤 4: 认知推演回路 · 专家大脑推演与技能决策
                sendTraceStep(emitter, isClientConnected, traceStepsMap, 4, "AGENT_EXECUTION_SUMMARY", "Agent 执行摘要", "DONE", Map.of(
                    "totalToolsExecuted", totalToolsExecuted
                ));

                // Emit a tool-execution event only when at least one tool actually ran.
                if (totalToolsExecuted > 0) {
                    Map<String, Object> toolSummary = new LinkedHashMap<>();
                    toolSummary.put("totalToolsExecuted", totalToolsExecuted);
                    if (hasPendingApproval) toolSummary.put("approvalStatus", "PENDING_APPROVAL");
                    sendTraceStep(emitter, isClientConnected, traceStepsMap, 5, "SKILL_ACTION", "工具执行", "DONE", toolSummary);
                }

                if (isClientConnected.get()) {
                    try {
                        String rText = finalIsGitOrCodeTask
                                ? "把工作区现场变动和最新提交串联起来，梳理出通俗易懂的解读，按规矩附上当前时间，现在开始整理汇报。\n\n"
                                : "思路已经理顺，现在开始生成详细回答。\n\n";
                        reasoningBuffer.append(rText);
                        emitter.send(SseEmitter.event().name("reasoning").data(rText));
                    } catch (IOException ignored) {}
                }

                // 6. 流式推流自然语言回答 (经由多模型故障转移网关 ModelGatewayService 调度)
                StringBuilder replyBuffer = new StringBuilder();
                java.util.concurrent.atomic.AtomicInteger promptTokensRef = new java.util.concurrent.atomic.AtomicInteger(0);
                java.util.concurrent.atomic.AtomicInteger completionTokensRef = new java.util.concurrent.atomic.AtomicInteger(0);
                java.util.concurrent.atomic.AtomicBoolean hasRealUsageRef = new java.util.concurrent.atomic.AtomicBoolean(false);
                java.util.concurrent.atomic.AtomicLong firstTokenTimeRef = new java.util.concurrent.atomic.AtomicLong(0);

                String finalModelStepId = "final-response-" + UUID.randomUUID().toString().substring(0, 8);
                activeModelStepId.set(finalModelStepId);
                Map<String, Object> finalRequestDetail = new LinkedHashMap<>();
                finalRequestDetail.put("model", rawModel != null ? rawModel : llmClientService.getConfig("LLM_MODEL", "configured-model"));
                finalRequestDetail.put("model_step", "final");
                if (isExecutionContentCaptureEnabled()) {
                    finalRequestDetail.put("prompt_messages", boundedTraceValue(messages));
                } else {
                    finalRequestDetail.put("content_capture", "disabled");
                }
                sendExecutionEvent(emitter, isClientConnected, traceStepsMap, finalModelStepId, 60,
                        "llm", "running", "最终回答生成", "正在将工具结果和上下文提交给模型", finalRequestDetail);

                ModelGatewayService.FailoverContext failoverCtx = modelGatewayService.streamChatWithFailover(
                        rawModel,
                        temperature,
                        messages,
                        delta -> {
                            if (firstTokenTimeRef.compareAndSet(0, System.currentTimeMillis())) {
                                long currentTtft = System.currentTimeMillis() - startTime;
                                sendTraceStep(emitter, isClientConnected, traceStepsMap, 6, "LLM_RESPONSE", "结果组装与流式交付", "STREAMING", Map.of("ttftMs", currentTtft));
                            }
                            if (isClientConnected.get()) {
                                try {
                                    replyBuffer.append(delta);
                                    emitter.send(SseEmitter.event().name("delta").data(delta));
                                } catch (IOException ignored) {}
                            }
                        },
                        reason -> {
                            if (isClientConnected.get()) {
                                try {
                                    reasoningBuffer.append(reason);
                                    emitter.send(SseEmitter.event().name("reasoning").data(reason));
                                } catch (IOException ignored) {}
                            }
                        },
                        (pTokens, cTokens) -> {
                            promptTokensRef.set(pTokens);
                            completionTokensRef.set(cTokens);
                            hasRealUsageRef.set(true);
                            dailyTotalTokens.addAndGet(pTokens + cTokens);
                        },
                        isClientConnected
                );

                long durationMs = System.currentTimeMillis() - startTime;
                String durationStr = String.format(Locale.US, "%.2fs", durationMs / 1000.0);
                Map<String, Object> finalResponseDetail = new LinkedHashMap<>(finalRequestDetail);
                if (isExecutionContentCaptureEnabled()) {
                    finalResponseDetail.put("model_response", boundedTraceValue(replyBuffer.toString()));
                } else {
                    finalResponseDetail.put("content_capture", "disabled");
                }
                finalResponseDetail.put("durationMs", durationMs);
                if (hasRealUsageRef.get()) {
                    finalResponseDetail.put("usage", Map.of(
                            "prompt_tokens", promptTokensRef.get(),
                            "completion_tokens", completionTokensRef.get(),
                            "usage_reported", true));
                } else {
                    finalResponseDetail.put("usage_reported", false);
                }
                finalResponseDetail.put("provider_route", failoverCtx.triggered ? failoverCtx.fallbackChannel : "primary");
                sendExecutionEvent(emitter, isClientConnected, traceStepsMap, finalModelStepId, 60,
                        "llm", "done", "最终回答生成", "模型流式回答已完成", finalResponseDetail);
                activeModelStepId.compareAndSet(finalModelStepId, null);
                chatRunRecorder.complete(promptTokensRef.get(), completionTokensRef.get(), hasRealUsageRef.get(), isClientConnected.get() ? "COMPLETED" : "ABORTED");

                // 7. 解决引用保存 Bug：setCitationsJson 必须在 save 之前赋值，确保持久化
                ChatMessageEntity asstMsg = new ChatMessageEntity();
                asstMsg.setId("msg-" + UUID.randomUUID().toString().substring(0, 8));
                asstMsg.setSessionId(sessionId);
                asstMsg.setRole("assistant");
                asstMsg.setContent(replyBuffer.toString());
                asstMsg.setReasoning(reasoningBuffer.length() > 0 ? reasoningBuffer.toString() : null);
                asstMsg.setTokens(hasRealUsageRef.get() ? completionTokensRef.get() : 0);
                asstMsg.setDuration(durationStr);
                if (citationsList != null && !citationsList.isEmpty()) {
                    try {
                        asstMsg.setCitationsJson(objectMapper.writeValueAsString(citationsList));
                    } catch (Exception ignored) {}
                }
                try {
                    asstMsg.setTraceStepsJson(objectMapper.writeValueAsString(traceStepsMap));
                    if (!toolCallsList.isEmpty()) {
                        asstMsg.setToolCallsJson(objectMapper.writeValueAsString(toolCallsList));
                    }
                    if (approvalTicketRef.get() != null) {
                        asstMsg.setApprovalTicketJson(objectMapper.writeValueAsString(approvalTicketRef.get()));
                    }
                    Map<String, Object> traceSummary = new LinkedHashMap<>();
                    traceSummary.putAll(chatRunRecorder.currentIdentity());
                    traceSummary.put("cached", false);
                    traceSummary.put("totalMs", durationMs);
                    traceSummary.put("tokens", asstMsg.getTokens());
                    asstMsg.setTraceJson(objectMapper.writeValueAsString(traceSummary));
                } catch (Exception ignored) {}
                messageRepository.save(asstMsg);

                // 8. Token 账单防虚标审计：若上游未返回真实 usage，不使用字符除以 2 伪造账单
                boolean wasAborted = !isClientConnected.get();
                AuditLogEntity audit = new AuditLogEntity();
                audit.setId("log-" + UUID.randomUUID().toString().substring(0, 8));
                audit.setTime(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                audit.setAgentName(activeAgentName);
                audit.setEventType(failoverCtx.triggered ? "FAILOVER_STREAM" : "CHAT_STREAM");
                audit.setModel(failoverCtx.triggered ? failoverCtx.fallbackChannel : (rawModel != null ? rawModel : llmClientService.getConfig("LLM_MODEL", "deepseek-chat")));
                audit.setDuration(durationStr);

                if (hasRealUsageRef.get()) {
                    audit.setTokensIn(promptTokensRef.get());
                    audit.setTokensOut(completionTokensRef.get());
                    audit.setIsRealUsage(true);
                } else {
                    audit.setTokensIn(0);
                    audit.setTokensOut(0);
                    audit.setIsRealUsage(false);
                }

                audit.setStatus(wasAborted ? "ABORTED" : "SUCCESS");
                auditLogRepository.save(audit);

                // 9. 异步事实提炼 (从真实对话提炼事实，绝不凭空捏造假偏好)
                agentMemoryService.asyncExtractFact(sessionId, messageText, replyBuffer.toString());

                // 10. 保存至 Exact Hash Cache (规范化 SHA-256 存储，仅限纯知识问答，动态实时/工具调用类严禁缓存)
                if (!wasAborted && !isDynamicStatusQuery && totalToolsExecuted == 0 && replyBuffer.length() >= 50) {
                    try {
                        String citJson = (citationsList != null && !citationsList.isEmpty()) ? objectMapper.writeValueAsString(citationsList) : null;
                        int tokenCost = hasRealUsageRef.get() ? completionTokensRef.get() : Math.max(1, replyBuffer.length() / 2);
                        exactHashCacheService.saveToCache(messageText, replyBuffer.toString(), reasoningBuffer.toString(), citJson, tokenCost, durationMs);
                    } catch (Exception ignored) {}
                }

                if (isClientConnected.get()) {
                    int finalTokens = hasRealUsageRef.get() ? completionTokensRef.get() : 0;
                    long firstTokenTs = firstTokenTimeRef.get();
                    long ttftMs = firstTokenTs > 0 ? (firstTokenTs - startTime) : (ragDurationMs + 200);
                    long streamMs = Math.max(0, durationMs - ttftMs);

                    Map<String, Object> traceMap = new LinkedHashMap<>();
                    traceMap.put("totalMs", durationMs);
                    traceMap.put("ragMs", ragDurationMs);
                    traceMap.put("ttftMs", ttftMs);
                    traceMap.put("streamMs", streamMs);
                    traceMap.put("hitCount", citationsList.size());
                    traceMap.put("cached", false);
                    traceMap.put("hitType", "NONE");
                    traceMap.put("hashKey", promptHashKey);
                    traceMap.put("savedTokens", 0);

                    if (searchResult != null) {
                        Map<String, Object> ragStatsMap = new LinkedHashMap<>();
                        ragStatsMap.put("totalDocs", searchResult.getTotalDocs());
                        ragStatsMap.put("bm25Candidates", searchResult.getBm25Candidates());
                        ragStatsMap.put("noiseFiltered", searchResult.getNoiseFiltered());
                        ragStatsMap.put("validCount", searchResult.getValidCount());
                        ragStatsMap.put("isRealVector", searchResult.isRealVector());
                        ragStatsMap.put("mode", searchResult.getMode());
                        traceMap.put("ragStats", ragStatsMap);
                    }

                    // 写入 Failover 真实审计溯源数据
                    if (failoverCtx != null && failoverCtx.triggered) {
                        Map<String, Object> failoverMap = new LinkedHashMap<>();
                        failoverMap.put("triggered", true);
                        failoverMap.put("primaryChannel", failoverCtx.primaryChannel);
                        failoverMap.put("fallbackChannel", failoverCtx.fallbackChannel);
                        failoverMap.put("reason", failoverCtx.reason);
                        failoverMap.put("switchLatencyMs", failoverCtx.switchLatencyMs);
                        failoverMap.put("circuitState", failoverCtx.circuitState);
                        traceMap.put("failover", failoverMap);
                    }

                    Map<String, Object> finishPayload = new LinkedHashMap<>();
                    finishPayload.put("tokens", finalTokens);
                    finishPayload.put("duration", durationStr);
                    finishPayload.put("cached", false);
                    finishPayload.put("savedTokens", 0);
                    finishPayload.put("trace", traceMap);

                    sendTraceStep(emitter, isClientConnected, traceStepsMap, 6, "STREAM_DELIVERY", "管控层 · 执行跟踪与 SSE 流式交付", "DONE", Map.of(
                        "totalMs", durationMs,
                        "ttftMs", ttftMs,
                        "streamMs", streamMs,
                        "tokens", finalTokens,
                        "duration", durationStr
                    ));

                    emitter.send(SseEmitter.event().name("finish").data(objectMapper.writeValueAsString(finishPayload)));
                    emitter.complete();
                }

            } catch (Exception e) {
                chatRunRecorder.fail(e);
                String activeStepId = activeModelStepId.getAndSet(null);
                if (activeStepId != null) {
                    Map<String, Object> failedDetail = new LinkedHashMap<>();
                    failedDetail.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                    failedDetail.put("error_type", e.getClass().getSimpleName());
                    sendExecutionEvent(emitter, isClientConnected, traceStepsMap, activeStepId, 60,
                            "llm", "failed", "模型调用失败", "模型调用未能正常完成", failedDetail);
                }
                if (isClientConnected.get()) {
                    try {
                        String errMsg = "\n\n【调用异常】" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                        emitter.send(SseEmitter.event().name("delta").data(errMsg));
                        emitter.send(SseEmitter.event().name("finish").data("{\"tokens\":0,\"duration\":\"0s\"}"));
                    } catch (IOException ignored) {}
                    emitter.complete();
                }
            } finally {
                chatRunRecorder.clear();
            }
        });

        return emitter;
    }

    @GetMapping("/settings/config")
    public Map<String, String> getSettingsConfig() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("LLM_API_KEY", llmClientService.getConfig("LLM_API_KEY", ""));
        map.put("LLM_BASE_URL", llmClientService.getConfig("LLM_BASE_URL", "https://api.deepseek.com/v1"));
        map.put("LLM_MODEL", llmClientService.getConfig("LLM_MODEL", "deepseek-chat"));
        map.put("LLM_NETWORK_MODE", llmClientService.getConfig("LLM_NETWORK_MODE", "LOCAL"));
        map.put("LLM_REMOTE_IP", llmClientService.getConfig("LLM_REMOTE_IP", "192.168.1.100"));
        map.put("EMBEDDING_MODEL", llmClientService.getConfig("EMBEDDING_MODEL", "nomic-embed-text"));
        map.put("EMBEDDING_BASE_URL", llmClientService.getConfig("EMBEDDING_BASE_URL", ""));
        map.put("EMBEDDING_API_KEY", llmClientService.getConfig("EMBEDDING_API_KEY", ""));
        map.put("DRAWER_WIDTH", llmClientService.getConfig("DRAWER_WIDTH", "540"));
        return map;
    }

    @PostMapping("/settings/config")
    public ResponseEntity<Map<String, Object>> saveSettingsConfig(@RequestBody Map<String, String> body) {
        body.forEach((k, v) -> {
            if (v != null && !v.trim().isEmpty() && !v.startsWith("sk-***")) {
                llmClientService.setConfig(k, v.trim());
            }
        });
        return ResponseEntity.ok(Map.of("ok", true, "msg", "配置已成功持久化至后端"));
    }

    @GetMapping("/knowledge/all")
    public List<KnowledgeChunk> getAllKnowledge() {
        return knowledgeRepository.findAllByOrderByCreatedAtDesc();
    }

    @PostMapping("/knowledge/upload")
    public Map<String, Object> uploadKnowledgeDocument(@RequestBody Map<String, String> body) {
        String title = body.getOrDefault("title", "未命名知识文档");
        String content = body.getOrDefault("content", "");
        String keywords = body.getOrDefault("keywords", "企业通用知识");
        String category = body.getOrDefault("category", "ENTERPRISE");

        Map<String, Object> ingestResult = hybridRagService.processAndIngestDocument(title, content, keywords, category);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("docTitle", title);
        resp.put("totalChunks", ingestResult.get("totalChunks"));
        resp.put("successChunks", ingestResult.get("successChunks"));
        resp.put("failedChunks", ingestResult.get("failedChunks"));
        resp.put("message", "文档切片流水线处理完成：共切分 " + ingestResult.get("totalChunks") + " 片，向量入库成功 " + ingestResult.get("successChunks") + " 片，失败 " + ingestResult.get("failedChunks") + " 片");
        return resp;
    }

    @PostMapping("/knowledge/search")
    public List<HybridRagService.RagChunk> searchKnowledge(@RequestBody Map<String, String> body) {
        String query = body.getOrDefault("query", "");
        return hybridRagService.hybridSearch(query, 5);
    }

    @DeleteMapping("/knowledge/{id}")
    public ResponseEntity<Void> deleteKnowledgeChunk(@PathVariable String id) {
        knowledgeRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/knowledge/{id}")
    public ResponseEntity<KnowledgeChunk> updateKnowledgeChunk(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        return knowledgeRepository.findById(id).map(chunk -> {
            if (body.containsKey("docTitle")) chunk.setDocTitle(body.get("docTitle"));
            if (body.containsKey("content")) chunk.setContent(body.get("content"));
            if (body.containsKey("keywords")) chunk.setKeywords(body.get("keywords"));
            if (body.containsKey("category")) chunk.setCategory(body.get("category"));
            return ResponseEntity.ok(knowledgeRepository.save(chunk));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/tools/toggle/{id}")
    public ResponseEntity<McpTool> toggleTool(@PathVariable String id) {
        return toolRepository.findById(id).map(t -> {
            t.setEnabled(!Boolean.TRUE.equals(t.getEnabled()));
            return ResponseEntity.ok(toolRepository.save(t));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/tools/test")
    public Map<String, Object> testTool(@RequestBody Map<String, Object> body) {
        long start = System.currentTimeMillis();
        String toolName = (String) body.getOrDefault("toolName", "validate_iso20022_iban");
        Object payload = body.get("payload");

        String payloadJson = "{}";
        try {
            if (payload instanceof String) {
                payloadJson = (String) payload;
            } else if (payload != null) {
                payloadJson = objectMapper.writeValueAsString(payload);
            }
        } catch (Exception e) {
            payloadJson = "{}";
        }

        ToolExecutor.ToolExecutionResult result = toolExecutor.executeTool(toolName, payloadJson, 0);
        long cost = System.currentTimeMillis() - start;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tool", toolName);
        data.put("status", result.isSuccess() ? "SUCCESS" : "FAILED");
        data.put("executedAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        data.put("inputPayload", payload);
        data.put("outputResult", result.isSuccess() ? result.getData() : result.getErrorMessage());

        AuditLogEntity audit = new AuditLogEntity();
        audit.setId("log-" + UUID.randomUUID().toString().substring(0, 8));
        audit.setTime(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        audit.setAgentName("MCP 插件测试");
        audit.setEventType("TOOL_CALL");
        audit.setToolName(toolName);
        audit.setParameters(payloadJson);
        audit.setModel(toolName);
        audit.setDuration(cost + "ms");
        audit.setTokensIn(0);
        audit.setTokensOut(0);
        audit.setStatus(result.isSuccess() ? "SUCCESS" : "FAILED");
        audit.setIsRealUsage(false);
        auditLogRepository.save(audit);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("code", result.isSuccess() ? 200 : 500);
        res.put("msg", result.isSuccess() ? "SUCCESS" : result.getErrorMessage());
        res.put("data", data);
        res.put("costMs", cost);
        return res;
    }

    @GetMapping("/audit/logs")
    public List<AuditLogEntity> getAuditLogs() {
        return auditLogRepository.findAllByOrderByCreatedAtDesc();
    }

    @GetMapping("/system/model-probe")
    public ResponseEntity<Map<String, Object>> probeModelProbe() {
        return ResponseEntity.ok(llmClientService.probeModelConnectivity());
    }

    @PostMapping("/settings/test-llm")
    public ResponseEntity<Map<String, Object>> testLlmConnection(@RequestBody(required = false) Map<String, String> body) {
        String baseUrl = body != null ? body.get("baseUrl") : null;
        String apiKey = body != null ? body.get("apiKey") : null;
        String model = body != null ? body.get("model") : null;
        Map<String, Object> result = llmClientService.testLlmConnection(baseUrl, apiKey, model);
        return ResponseEntity.ok(result);
    }


    @PostMapping("/settings/fetch-models")
    public ResponseEntity<Map<String, Object>> fetchModels(@RequestBody(required = false) Map<String, String> body) {
        String baseUrl = body != null ? body.get("baseUrl") : null;
        String apiKey = body != null ? body.get("apiKey") : null;
        Map<String, Object> result = llmClientService.fetchRemoteModels(baseUrl, apiKey);
        return ResponseEntity.ok(result);
    }

    // ==============================================================================
    // 生产级 Human-in-the-Loop 人机协同审批接口 (高危写操作人工核准与断点续跑)
    // ==============================================================================

    @GetMapping("/approval/tickets")
    public ResponseEntity<?> getAllApprovalTickets(
            @RequestHeader(name = "X-OwnAgent-Approver-Key", required = false) String approverKey) {
        if (!approvalAuthorizationService.isAuthorized(approverKey)) {
            return ResponseEntity.status(403).body(Map.of("error", "Approver authentication required"));
        }
        if (approvalTicketRepository == null) return ResponseEntity.ok(Collections.emptyList());
        return ResponseEntity.ok(approvalTicketRepository.findAll());
    }

    /**
     * Legacy approval endpoint disabled: it executed SQL text stored in an approval ticket
     * directly through JdbcTemplate. Approval must never be equivalent to arbitrary SQL execution.
     */
    @PostMapping("/approval/decide")
    public ResponseEntity<Map<String, Object>> decideApproval(@RequestBody(required = false) Map<String, String> body) {
        return ResponseEntity.status(410).body(Map.of(
                "error", "LEGACY_APPROVAL_DISABLED",
                "message", "Direct SQL execution from approval tickets is disabled. Use the authenticated workflow approval API and a registered, policy-controlled tool."
        ));
    }

    // ══════════════ 多模型故障转移 (Failover) 断路器与演练管控 ══════════════

    @GetMapping("/failover/status")
    public ResponseEntity<Map<String, Object>> getFailoverStatus() {
        return ResponseEntity.ok(modelGatewayService.getGatewayStatus());
    }

    @PostMapping("/failover/toggle-timeout")
    public ResponseEntity<Map<String, Object>> toggleFailoverTimeout(@RequestBody(required = false) Map<String, Object> body) {
        boolean current = modelGatewayService.isSimulatePrimaryTimeout();
        boolean nextVal = (body != null && body.containsKey("enabled"))
                ? Boolean.TRUE.equals(body.get("enabled"))
                : !current;
        modelGatewayService.setSimulatePrimaryTimeout(nextVal);
        return ResponseEntity.ok(modelGatewayService.getGatewayStatus());
    }

    @PostMapping("/failover/reset")
    public ResponseEntity<Map<String, Object>> resetFailover() {
        modelGatewayService.resetCircuit();
        return ResponseEntity.ok(modelGatewayService.getGatewayStatus());
    }

    private boolean isExecutionContentCaptureEnabled() {
        String value = System.getenv("OWNAGENT_TRACE_CAPTURE_CONTENT");
        return value != null && Set.of("1", "true", "yes").contains(value.trim().toLowerCase(Locale.ROOT));
    }

    private Object boundedTraceValue(Object value) {
        if (value == null) return null;
        try {
            String json = objectMapper.writeValueAsString(value);
            if (json.length() <= 24000) return value;
            return json.substring(0, 24000) + " … [truncated by OwnAgent]";
        } catch (Exception ignored) {
            String text = String.valueOf(value);
            return text.length() <= 24000 ? text : text.substring(0, 24000) + " … [truncated by OwnAgent]";
        }
    }

    private Object parseJsonOrText(String value) {
        if (value == null) return null;
        try { return objectMapper.readValue(value, Object.class); }
        catch (Exception ignored) { return value; }
    }

    private void sendExecutionEvent(SseEmitter emitter, AtomicBoolean isConnected,
                                    Map<Integer, Object> traceStepsMap, String stepId,
                                    int seq, String phase, String status, String title,
                                    String subtitle, Map<String, Object> details) {
        if (isConnected != null && !isConnected.get()) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stepId", stepId);
        payload.put("seq", seq);
        payload.put("phase", phase);
        payload.put("status", status);
        payload.put("leftTitle", title);
        payload.put("leftSummary", subtitle);
        payload.put("title", title);
        payload.put("rightTitle", title);
        payload.put("rightSubtitle", subtitle);
        payload.put("rightDetail", details);
        payload.put("timestamp", System.currentTimeMillis());
        if (traceStepsMap != null) traceStepsMap.put(seq, payload);
        chatRunRecorder.recordStep(stepId, seq, phase, status, title, subtitle, details);
        try {
            String json = objectMapper.writeValueAsString(payload);
            emitter.send(SseEmitter.event().name("agent_step").data(json));
            emitter.send(SseEmitter.event().name("trace_step").data(json));
        } catch (Exception ignored) {}
    }

    /**
     * Emit a compact event marker for an operation performed by the backend.
     * Event codes and attached details are the evidence. The UI must not turn
     * these markers into invented reasoning stages, confidence scores, or sandbox claims.
     */
    private void sendTraceStep(SseEmitter emitter, AtomicBoolean isConnected,
                               Map<Integer, Object> traceStepsMap, int step,
                               String name, String title, String status,
                               Map<String, Object> details) {
        String eventName = name == null || name.isBlank() ? "UNKNOWN_EVENT" : name;
        String phase = switch (eventName) {
            case "CLIENT_REQUEST" -> "intent";
            case "INTENT_ROUTER" -> "workflow";
            case "FINOPS_CACHE", "HYBRID_RAG", "CONTEXT_HYDRATION" -> "context";
            case "LLM_REASONING", "LLM_RESPONSE" -> "llm";
            case "AGENT_EXECUTION_SUMMARY" -> "workflow";
            case "SKILL_ACTION" -> "skill";
            case "STREAM_DELIVERY" -> "delivery";
            default -> "external";
        };
        String label = switch (eventName) {
            case "CLIENT_REQUEST" -> "用户请求";
            case "INTENT_ROUTER" -> "任务路由";
            case "FINOPS_CACHE" -> "缓存检查";
            case "HYBRID_RAG" -> "知识检索";
            case "CONTEXT_HYDRATION" -> "上下文准备";
            case "LLM_REASONING" -> "模型调用";
            case "LLM_RESPONSE" -> "模型响应";
            case "AGENT_EXECUTION_SUMMARY" -> "Agent 执行摘要";
            case "SKILL_ACTION" -> "工具执行";
            case "STREAM_DELIVERY" -> "响应输出";
            case "SAFETY_BASELINE" -> "安全策略";
            default -> eventName;
        };

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("seq", step);
        payload.put("step", step);
        payload.put("name", eventName);
        payload.put("stepId", "step-" + step + "-" + eventName.toLowerCase(Locale.ROOT).replace('_', '-'));
        payload.put("title", label);
        payload.put("status", status == null ? "UNKNOWN" : status);
        payload.put("timestamp", System.currentTimeMillis());
        payload.put("phase", phase);
        payload.put("leftTitle", label);
        payload.put("leftSummary", "");
        payload.put("rightTitle", label);
        payload.put("rightSubtitle", "");
        payload.put("rightDetail", details != null ? details : Collections.emptyMap());
        if (details != null) payload.putAll(details);

        if (traceStepsMap != null) traceStepsMap.put(step, payload);
        chatRunRecorder.recordStep("step-" + step + "-" + eventName.toLowerCase(Locale.ROOT).replace('_', '-'), step, phase, status, label, "", details);
        if (isConnected != null && !isConnected.get()) return;
        try {
            String json = objectMapper.writeValueAsString(payload);
            emitter.send(SseEmitter.event().name("agent_step").data(json));
            emitter.send(SseEmitter.event().name("trace_step").data(json));
        } catch (Exception ignored) {}
    }

}
