package com.ownsudo.service;

import com.ownsudo.entity.AgentMemory;
import com.ownsudo.repository.AgentMemoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工业级长期记忆中台服务 (AgentMemoryService)
 * 对应实体: com.ownsudo.entity.AgentMemory
 * 严格落实工业级防假偏好规范：
 * 1. 严格门槛：仅在用户明确立规矩（如「我偏好」「我们规定」「必须使用」「不要用」）时写入，普通技术问答绝不沉淀；
 * 2. 幂等更新：按「sessionId + 实体类别」更新同一行，绝不重复追加垃圾行；
 * 3. 作用域隔离：范围仅限定在当前会话 (sessionId)，杜绝无脑污染全局；
 * 4. 相关性召回与长度熔断：当前提问必须与偏好实体/事实产生交集，每条严格限制 80 字符，最多注入 2 条。
 */
@Service
public class AgentMemoryService {

    @Autowired(required = false)
    private AgentMemoryRepository agentMemoryRepository;

    private final ExecutorService asyncWorker = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 1. 异步从对话交互中提炼规则偏好 (必须是明确立规矩的句式)
     */
    public void asyncExtractFact(String sessionId, String userQuery, String assistantReply) {
        if (userQuery == null || userQuery.isBlank() || sessionId == null || sessionId.isBlank()) return;

        asyncWorker.submit(() -> {
            try {
                String trimmed = userQuery.trim();

                // 门槛校验：仅当用户明确立规矩或声明偏好时才触发提取
                boolean isExplicitRule = trimmed.startsWith("我偏好")
                        || trimmed.startsWith("我的技术栈是")
                        || trimmed.startsWith("我们规定")
                        || trimmed.startsWith("规定必须")
                        || trimmed.startsWith("强制要求")
                        || trimmed.contains("必须使用")
                        || trimmed.contains("要求必须使用")
                        || trimmed.contains("不要用")
                        || trimmed.contains("严禁使用")
                        || trimmed.contains("统一采用");

                if (!isExplicitRule) {
                    return; // 普通技术交流或提问（即使提到 Java、Spring、架构）坚决不入库
                }

                String entityCategory = "企业业务约束";
                if (trimmed.contains("技术栈") || trimmed.contains("语言") || trimmed.contains("Java") ||
                    trimmed.contains("Spring") || trimmed.contains("Python") || trimmed.contains("Go") ||
                    trimmed.contains("Vue") || trimmed.contains("React")) {
                    entityCategory = "技术栈规范";
                } else if (trimmed.contains("数据库") || trimmed.contains("MySQL") || trimmed.contains("Postgre") ||
                           trimmed.contains("Redis") || trimmed.contains("JPA") || trimmed.contains("MyBatis")) {
                    entityCategory = "持久层规范";
                } else if (trimmed.contains("架构") || trimmed.contains("微服务") || trimmed.contains("单体") ||
                           trimmed.contains("分布式") || trimmed.contains("限流") || trimmed.contains("高可用")) {
                    entityCategory = "架构设计规范";
                } else if (trimmed.contains("禁止") || trimmed.contains("只允许") || trimmed.contains("不要用") ||
                           trimmed.contains("安全规范")) {
                    entityCategory = "安全合规约束";
                }

                String structuredFact = extractCoreStatement(trimmed);
                saveOrUpdateMemoryFact(sessionId, entityCategory, structuredFact);
            } catch (Exception e) {
                System.err.println("[AgentMemory] 异步事实提炼异常: " + e.getMessage());
            }
        });
    }

    private String extractCoreStatement(String text) {
        // 清理语气助词与问候语
        String clean = text.replaceAll("^(请问|你好|麻烦|请帮我|我想问|我们要求)", "").trim();
        if (clean.length() > 80) {
            clean = clean.substring(0, 80) + "...";
        }
        return clean;
    }

    /**
     * 2. 幂等更新：按「sessionId + 实体类别」更新同一行，绝不重复追加
     */
    public void saveOrUpdateMemoryFact(String sessionId, String referenceEntity, String content) {
        if (agentMemoryRepository == null || sessionId == null || sessionId.isBlank()) return;

        Optional<AgentMemory> existingOpt = agentMemoryRepository.findBySessionIdAndReferenceEntity(sessionId, referenceEntity);
        AgentMemory memory = existingOpt.orElseGet(() -> {
            AgentMemory m = new AgentMemory();
            m.setId("mem-" + UUID.randomUUID().toString().substring(0, 8));
            m.setSessionId(sessionId);
            m.setMemoryType("SEMANTIC");
            m.setReferenceEntity(referenceEntity);
            m.setCreatedAt(LocalDateTime.now());
            return m;
        });

        memory.setContent(content);
        memory.setImportanceScore(1.0);
        memory.setLastAccessedAt(LocalDateTime.now());
        agentMemoryRepository.save(memory);
        System.out.println("[AgentMemory] 成功幂等更新偏好事实 [" + sessionId + " | " + referenceEntity + "]: " + content);
    }

    /**
     * 3. 查询与当前提问强相关的真实会话记忆 (语义与关键词匹配，不相关坚决不召回)
     */
    public List<String> recallRelevantFacts(String sessionId, String currentQuery) {
        if (agentMemoryRepository == null || sessionId == null || sessionId.isBlank() || currentQuery == null || currentQuery.isBlank()) {
            return Collections.emptyList();
        }

        try {
            // 范围严格限定在当前会话
            List<AgentMemory> candidates = agentMemoryRepository.findBySessionIdOrderByLastAccessedAtDesc(sessionId);
            if (candidates == null || candidates.isEmpty()) {
                return Collections.emptyList();
            }

            Set<String> queryTokens = extractQueryTokens(currentQuery);
            if (queryTokens.isEmpty()) {
                return Collections.emptyList();
            }

            class ScoredMemory {
                final AgentMemory memory;
                final int score;
                ScoredMemory(AgentMemory memory, int score) {
                    this.memory = memory;
                    this.score = score;
                }
            }

            List<ScoredMemory> scoredList = new ArrayList<>();
            for (AgentMemory m : candidates) {
                if (m.getContent() == null || m.getContent().isBlank()) continue;
                int matchScore = 0;
                String targetText = ((m.getReferenceEntity() != null ? m.getReferenceEntity() : "") + " " + m.getContent()).toLowerCase();
                for (String token : queryTokens) {
                    if (targetText.contains(token.toLowerCase())) {
                        matchScore += (token.length() >= 3 ? 2 : 1);
                    }
                }

                if (matchScore > 0) {
                    scoredList.add(new ScoredMemory(m, matchScore));
                }
            }

            if (scoredList.isEmpty()) {
                return Collections.emptyList();
            }

            // 按相关度降序，次序按更新时间降序，最多取 Top 2
            scoredList.sort((a, b) -> {
                int c = Integer.compare(b.score, a.score);
                if (c != 0) return c;
                return b.memory.getLastAccessedAt().compareTo(a.memory.getLastAccessedAt());
            });

            List<String> results = new ArrayList<>();
            for (int i = 0; i < Math.min(2, scoredList.size()); i++) {
                AgentMemory m = scoredList.get(i).memory;
                m.setLastAccessedAt(LocalDateTime.now());
                agentMemoryRepository.save(m);

                String text = m.getContent();
                if (text.length() > 80) {
                    text = text.substring(0, 80) + "...";
                }
                results.add("[" + (m.getReferenceEntity() != null ? m.getReferenceEntity() : "偏好") + "] " + text);
            }

            return results;
        } catch (Exception e) {
            System.err.println("[AgentMemory] 记忆召回异常: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private Set<String> extractQueryTokens(String query) {
        Set<String> tokens = new HashSet<>();
        Matcher m = Pattern.compile("[a-zA-Z0-9_]{2,}|[\\u4e00-\\u9fa5]{2,4}").matcher(query);
        while (m.find()) {
            String token = m.group().trim();
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /**
     * 4. 构造注入大模型的精简 System Prompt (严格约束 Token 增量 <= 150)
     */
    public String buildMemoryInjectedPrompt(String baseSystemPrompt, List<String> facts) {
        if (facts == null || facts.isEmpty()) {
            return baseSystemPrompt;
        }

        StringBuilder sb = new StringBuilder(baseSystemPrompt != null ? baseSystemPrompt : "");
        sb.append("\n\n### 当前会话用户明确偏好与约束 (从 agent_memory 召回):\n");
        for (int i = 0; i < facts.size(); i++) {
            sb.append(i + 1).append(". ").append(facts.get(i)).append("\n");
        }
        return sb.toString();
    }
}
