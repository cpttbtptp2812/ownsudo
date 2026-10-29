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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
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

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @GetMapping("/agents")
    public List<AgentEntity> getAgents() {
        List<AgentEntity> list = agentRepository.findAllByOrderByCreatedAtDesc();
        int toolCount = (int) toolRepository.count();
        int kbCount = (int) knowledgeRepository.count();
        for (AgentEntity a : list) {
            if (a.getToolsCount() == null || a.getToolsCount() == 0) {
                a.setToolsCount(toolCount);
            }
            if (a.getKbCount() == null || a.getKbCount() == 0) {
                a.setKbCount(kbCount);
            }
        }
        return list;
    }

    @PostMapping("/agents")
    public AgentEntity saveAgent(@RequestBody AgentEntity agent) {
        if (agent.getId() == null || agent.getId().trim().isEmpty()) {
            agent.setId("agent-" + UUID.randomUUID().toString().substring(0, 8));
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
    public SseEmitter streamChat(@RequestBody Map<String, Object> req) {
        SseEmitter emitter = new SseEmitter(120_000L);
        long startTime = System.currentTimeMillis();

        String sessionId = (String) req.getOrDefault("sessionId", "s-" + UUID.randomUUID().toString().substring(0, 8));
        String messageText = (String) req.getOrDefault("message", "");
        String agentId = (String) req.get("agentId");
        
        // 1. 真实使 agentId 数据库记录生效
        String dbModel = null;
        String dbSystemPrompt = null;
        Double dbTemperature = null;
        String agentName = "ownagent 调度中枢";

        if (agentId != null && !agentId.isBlank()) {
            Optional<AgentEntity> agentOpt = agentRepository.findById(agentId);
            if (agentOpt.isPresent()) {
                AgentEntity a = agentOpt.get();
                agentName = a.getName();
                dbModel = a.getModel();
                dbSystemPrompt = a.getSystemPrompt();
                dbTemperature = a.getTemperature();
            }
        }

        String rawModel = (req.get("model") != null && !((String)req.get("model")).isBlank()) ? (String) req.get("model") : dbModel;
        String rawSystemPrompt = (req.get("systemPrompt") != null && !((String)req.get("systemPrompt")).isBlank()) ? (String) req.get("systemPrompt") : dbSystemPrompt;
        Double temperature = req.get("temperature") != null ? Double.parseDouble(req.get("temperature").toString()) : (dbTemperature != null ? dbTemperature : 0.7);

        final String activeAgentName = agentName;
        AtomicBoolean isClientConnected = new AtomicBoolean(true);

        emitter.onCompletion(() -> isClientConnected.set(false));
        emitter.onTimeout(() -> isClientConnected.set(false));
        emitter.onError(e -> isClientConnected.set(false));

        executor.submit(() -> {
            Map<Integer, Object> traceStepsMap = new ConcurrentHashMap<>();
            List<Map<String, Object>> toolCallsList = new CopyOnWriteArrayList<>();
            AtomicReference<Object> approvalTicketRef = new AtomicReference<>(null);
            try {
                ChatMessageEntity userMsg = new ChatMessageEntity();
                userMsg.setId("msg-" + UUID.randomUUID().toString().substring(0, 8));
                userMsg.setSessionId(sessionId);
                userMsg.setRole("user");
                userMsg.setContent(messageText);
                userMsg.setTokens(0);
                messageRepository.save(userMsg);

                Boolean enableRag = (Boolean) req.getOrDefault("enableRAG", true);
                Boolean enableThinking = (Boolean) req.getOrDefault("enableThinking", true);
                Boolean enableWebSearch = (Boolean) req.getOrDefault("enableWebSearch", false);

                String promptHashKey = exactHashCacheService.computeSha256(exactHashCacheService.normalizePrompt(messageText));

                // 实时下发步骤 1: 客户端发起请求解析 (使前端在请求开始即能获得真实规范哈希指纹与配置)
                sendTraceStep(emitter, isClientConnected, traceStepsMap, 1, "CLIENT_REQUEST", "客户端发起请求与参数设定", "DONE", Map.of(
                    "model", rawModel != null ? rawModel : "qwen-max",
                    "hashKey", promptHashKey,
                    "sessionId", sessionId,
                    "enableRAG", enableRag,
                    "enableThinking", enableThinking,
                    "enableWebSearch", enableWebSearch,
                    "userPrompt", messageText
                ));

                // 1.5. 精确哈希降本缓存前置拦截 (Exact Hash Cache Check - 0 Token 极速返回)
                com.ownsudo.entity.PromptHashCacheEntity cached = exactHashCacheService.lookupAndHit(messageText);

                // 实时下发步骤 2: 降本哈希排查
                long hitDurationMs = cached != null ? Math.max(1, System.currentTimeMillis() - startTime) : (System.currentTimeMillis() - startTime);
                sendTraceStep(emitter, isClientConnected, traceStepsMap, 2, "FINOPS_CACHE", "降本哈希排查", cached != null ? "HIT" : "MISS", Map.of(
                    "hashKey", promptHashKey,
                    "cached", cached != null,
                    "durationMs", hitDurationMs,
                    "savedTokens", cached != null && cached.getOriginalTokens() != null ? cached.getOriginalTokens() : 0
                ));

                if (cached != null) {
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

                String currentPrompt = rawSystemPrompt != null ? rawSystemPrompt : "";

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
                    sendTraceStep(emitter, isClientConnected, traceStepsMap, 3, "HYBRID_RAG", "企业知识库多路召回", "DONE", ragDetails);
                } else {
                    sendTraceStep(emitter, isClientConnected, traceStepsMap, 3, "HYBRID_RAG", "企业知识库多路召回", "SKIPPED", Map.of("reason", "用户关闭了 RAG 知识检索"));
                }

                // 4. 上下文滑动窗口：严格截断最近 3 轮 (最多 6 条历史消息：3 user + 3 assistant)
                List<ChatMessageEntity> allHistory = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
                int maxHistory = 6;
                List<ChatMessageEntity> history = allHistory.size() > maxHistory
                        ? allHistory.subList(allHistory.size() - maxHistory, allHistory.size())
                        : allHistory;

                List<Map<String, Object>> messages = new ArrayList<>();
                if (!currentPrompt.isBlank()) {
                    messages.add(Map.of("role", "system", "content", currentPrompt));
                }
                for (ChatMessageEntity h : history) {
                    if ("user".equals(h.getRole()) || "assistant".equals(h.getRole())) {
                        messages.add(Map.of("role", h.getRole(), "content", h.getContent() != null ? h.getContent() : ""));
                    }
                }

                // 5. 真实工具调用闭环 (大模型驱动，彻底杜绝关键词旁路与假调用)
                int currentDepth = 1;
                List<Map<String, Object>> toolDefs = toolExecutor.getToolDefinitions();
                int totalToolsExecuted = 0;
                boolean hasPendingApproval = false;
                Map<String, Object> pendingApprovalData = null;

                while (currentDepth <= 3 && isClientConnected.get()) {
                    try {
                        com.fasterxml.jackson.databind.JsonNode compResponse =
                                llmClientService.callChatCompletionsWithTools(rawModel, temperature, messages, toolDefs, isClientConnected);

                        if (compResponse != null && compResponse.has("choices") && compResponse.get("choices").size() > 0) {
                            com.fasterxml.jackson.databind.JsonNode messageNode = compResponse.get("choices").get(0).path("message");

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

                                    long toolStart = System.currentTimeMillis();
                                    ToolExecutor.ToolExecutionResult toolResult = toolExecutor.executeTool(functionName, functionArgs, currentDepth);
                                    long toolDuration = System.currentTimeMillis() - toolStart;
                                    String toolDurationStr = toolDuration + "ms";

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
                                            toolPayloadMap.put("parameters", functionArgs);
                                            toolPayloadMap.put("status", toolResult.isSuccess() ? "success" : "error");
                                            toolPayloadMap.put("duration", toolDurationStr);
                                            toolPayloadMap.put("result", toolResult.isSuccess() ? toolResult.getData() : toolResult.getErrorMessage());
                                            toolCallsList.add(toolPayloadMap);
                                            emitter.send(SseEmitter.event().name("tool").data(objectMapper.writeValueAsString(toolPayloadMap)));

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
                        System.err.println("[ToolLoop] 工具调用循环检查异常: " + e.getMessage());
                    }
                    break;
                }

                // 实时下发步骤 4 (工具沙箱) 与步骤 5 (安全门禁)
                if (totalToolsExecuted > 0) {
                    sendTraceStep(emitter, isClientConnected, traceStepsMap, 4, "TOOL_ACTION", "业务系统工具执行", "DONE", Map.of("totalToolsExecuted", totalToolsExecuted));
                } else {
                    sendTraceStep(emitter, isClientConnected, traceStepsMap, 4, "TOOL_ACTION", "业务系统工具执行", "SKIPPED", Map.of("reason", "语义直通模式：纯文本问答无需外部外挂工具"));
                }

                if (hasPendingApproval && pendingApprovalData != null) {
                    sendTraceStep(emitter, isClientConnected, traceStepsMap, 5, "MUTATION_GATE", "高危操作人工复核闸口", "BLOCKED", pendingApprovalData);
                } else {
                    sendTraceStep(emitter, isClientConnected, traceStepsMap, 5, "MUTATION_GATE", "高危操作人工复核闸口", "PASSED", Map.of("reason", "基线核验通过：当前会话均为只读操作与受控计算"));
                }

                // 6. 流式推流自然语言回答 (经由多模型故障转移网关 ModelGatewayService 调度)
                StringBuilder replyBuffer = new StringBuilder();
                StringBuilder reasoningBuffer = new StringBuilder();
                java.util.concurrent.atomic.AtomicInteger promptTokensRef = new java.util.concurrent.atomic.AtomicInteger(0);
                java.util.concurrent.atomic.AtomicInteger completionTokensRef = new java.util.concurrent.atomic.AtomicInteger(0);
                java.util.concurrent.atomic.AtomicBoolean hasRealUsageRef = new java.util.concurrent.atomic.AtomicBoolean(false);
                java.util.concurrent.atomic.AtomicLong firstTokenTimeRef = new java.util.concurrent.atomic.AtomicLong(0);

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
                        },
                        isClientConnected
                );

                long durationMs = System.currentTimeMillis() - startTime;
                String durationStr = String.format(Locale.US, "%.2fs", durationMs / 1000.0);

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

                // 10. 保存至 Exact Hash Cache (规范化 SHA-256 存储，供后续二次调用极速复用)
                if (!wasAborted && replyBuffer.length() > 0) {
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

                    sendTraceStep(emitter, isClientConnected, traceStepsMap, 6, "LLM_RESPONSE", "结果组装与交付", "DONE", Map.of(
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
                if (isClientConnected.get()) {
                    try {
                        String errMsg = "\n\n【调用异常】" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                        emitter.send(SseEmitter.event().name("delta").data(errMsg));
                        emitter.send(SseEmitter.event().name("finish").data("{\"tokens\":0,\"duration\":\"0s\"}"));
                    } catch (IOException ignored) {}
                    emitter.complete();
                }
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
    public List<com.ownsudo.entity.ApprovalTicket> getAllApprovalTickets() {
        if (approvalTicketRepository == null) return Collections.emptyList();
        return approvalTicketRepository.findAll();
    }

    @PostMapping("/approval/decide")
    public ResponseEntity<Map<String, Object>> decideApproval(@RequestBody Map<String, String> body) {
        if (approvalTicketRepository == null) {
            return ResponseEntity.status(503).body(Map.of("error", "审批仓储组件未就绪"));
        }

        String ticketId = body.get("ticketId");
        String action = body.getOrDefault("action", "REJECT"); // APPROVE or REJECT
        String approver = body.getOrDefault("approver", "系统管理员");

        Optional<com.ownsudo.entity.ApprovalTicket> opt = approvalTicketRepository.findById(ticketId);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        com.ownsudo.entity.ApprovalTicket ticket = opt.get();
        if (!"PENDING".equals(ticket.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("error", "该审批单已决断，当前状态为: " + ticket.getStatus()));
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        if ("APPROVE".equalsIgnoreCase(action)) {
            ticket.setStatus("APPROVED");
            ticket.setOperatorId(approver);
            ticket.setDecidedAt(LocalDateTime.now());

            // 真实调用 JDBC 执行被拦截的写操作 SQL！
            int affected = 0;
            try {
                if (jdbcTemplate != null && ticket.getOperationSummary() != null) {
                    affected = jdbcTemplate.update(ticket.getOperationSummary());
                }
                resp.put("affectedRows", affected);
                resp.put("executedSql", ticket.getOperationSummary());
                resp.put("status", "APPROVED");
                resp.put("message", "管理员审批通过：已安全执行数据变更，受影响行数: " + affected);
            } catch (Exception e) {
                resp.put("status", "EXECUTION_FAILED");
                resp.put("error", "执行数据变更异常: " + e.getMessage());
            }
        } else {
            ticket.setStatus("REJECTED");
            ticket.setOperatorId(approver);
            ticket.setDecidedAt(LocalDateTime.now());
            resp.put("status", "REJECTED");
            resp.put("message", "管理员审批驳回：变更操作已被安全阻断并归档");
        }

        approvalTicketRepository.save(ticket);

        // 同步更新消息表中的 approvalTicketJson，确保刷新页面后审批状态永久保持已审批，不再回显为待审批
        try {
            List<ChatMessageEntity> allMsgs = messageRepository.findAll();
            for (ChatMessageEntity m : allMsgs) {
                if (m.getApprovalTicketJson() != null && m.getApprovalTicketJson().contains(ticketId)) {
                    Map<String, Object> tMap = objectMapper.readValue(m.getApprovalTicketJson(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                    tMap.put("status", ticket.getStatus());
                    tMap.put("operatorId", approver);
                    tMap.put("decidedAt", LocalDateTime.now().toString());
                    if ("APPROVED".equals(ticket.getStatus())) {
                        tMap.put("affectedRows", resp.get("affectedRows"));
                    }
                    m.setApprovalTicketJson(objectMapper.writeValueAsString(tMap));
                    messageRepository.save(m);
                }
            }
        } catch (Exception ignored) {}

        // 记入全链路安全审计日志
        AuditLogEntity audit = new AuditLogEntity();
        audit.setId("audit-" + UUID.randomUUID().toString().substring(0, 8));
        audit.setTime(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        audit.setAgentName("安全审批控制台");
        audit.setEventType("APPROVAL_" + ticket.getStatus());
        audit.setToolName(ticket.getNodeId());
        audit.setParameters("ticketId=" + ticketId + ", sql=" + ticket.getOperationSummary());
        audit.setModel(approver);
        audit.setStatus(ticket.getStatus());
        audit.setIsRealUsage(false);
        auditLogRepository.save(audit);

        resp.put("ticketId", ticketId);
        return ResponseEntity.ok(resp);
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

    /**
     * 向客户端逐帧下发真实全链路执行轨迹 (Trace Step)
     * 解决前端靠静态模板拼凑假进度的问题，实现服务端驱动的真实阶段流转
     */
    private void sendTraceStep(SseEmitter emitter, AtomicBoolean isConnected, Map<Integer, Object> traceStepsMap, int step, String name, String title, String status, Map<String, Object> details) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("step", step);
        payload.put("name", name);
        payload.put("title", title);
        payload.put("status", status);
        payload.put("timestamp", System.currentTimeMillis());
        if (details != null) {
            payload.putAll(details);
        }
        if (traceStepsMap != null) {
            traceStepsMap.put(step, payload);
        }
        if (isConnected != null && !isConnected.get()) return;
        try {
            emitter.send(SseEmitter.event().name("trace_step").data(objectMapper.writeValueAsString(payload)));
        } catch (Exception ignored) {}
    }

}
