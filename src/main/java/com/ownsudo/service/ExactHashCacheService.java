package com.ownsudo.service;

import com.ownsudo.entity.PromptHashCacheEntity;
import com.ownsudo.repository.PromptHashCacheRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 精确哈希降本缓存核心服务
 * 提供 Prompt 规范化清洗、SHA-256 签名生成、毫秒级命中查询与 FinOps 账单对账
 */
@Service
public class ExactHashCacheService {

    @Autowired
    private PromptHashCacheRepository cacheRepository;

    /**
     * 对原始提问进行规范化标准化处理：
     * 1. 去除首尾与连续冗余空格、换行
     * 2. 去除末尾常见中英文标点符号 (？?。!！等)
     * 3. 统一转小写，消除大小写冗余
     */
    public String normalizePrompt(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        // 替换所有连续空白/换行为单空格
        s = s.replaceAll("\\s+", " ");
        // 去除尾部常见标点符号
        s = s.replaceAll("[\\?\\!\\.。!！,，；;]+$", "").trim();
        return s.toLowerCase(Locale.ROOT);
    }

    /**
     * 计算文本的 SHA-256 哈希值
     */
    public String computeSha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            // 理论上不可能发生
            return String.valueOf(text.hashCode());
        }
    }

    /**
     * 尝试命中缓存
     * 若命中，递增 hit_count 与 total_saved_tokens 并持久化
     */
    @Transactional
    public PromptHashCacheEntity lookupAndHit(String rawPrompt) {
        String normalized = normalizePrompt(rawPrompt);
        if (normalized.isBlank()) return null;

        String hashKey = computeSha256(normalized);
        Optional<PromptHashCacheEntity> opt = cacheRepository.findById(hashKey);

        if (opt.isPresent()) {
            PromptHashCacheEntity entry = opt.get();
            entry.setHitCount(entry.getHitCount() + 1);
            int origTokens = entry.getOriginalTokens() != null ? entry.getOriginalTokens() : 0;
            entry.setTotalSavedTokens(entry.getTotalSavedTokens() + origTokens);
            entry.setLastHitAt(LocalDateTime.now());
            return cacheRepository.save(entry);
        }

        return null;
    }

    /**
     * 保存真实生成的产物到缓存
     */
    @Transactional
    public PromptHashCacheEntity saveToCache(String rawPrompt, String content, String reasoning, String citationsJson, int tokens, long durationMs) {
        String normalized = normalizePrompt(rawPrompt);
        if (normalized.isBlank() || content == null || content.isBlank()) {
            return null;
        }

        String hashKey = computeSha256(normalized);

        PromptHashCacheEntity entity = cacheRepository.findById(hashKey).orElseGet(() -> {
            PromptHashCacheEntity e = new PromptHashCacheEntity();
            e.setHashKey(hashKey);
            e.setNormalizedPrompt(normalized);
            e.setRawPrompt(rawPrompt);
            e.setHitCount(0);
            e.setTotalSavedTokens(0L);
            return e;
        });

        entity.setContent(content);
        entity.setReasoning(reasoning);
        entity.setCitationsJson(citationsJson);
        entity.setOriginalTokens(tokens);
        entity.setOriginalDurationMs(durationMs);
        entity.setLastHitAt(LocalDateTime.now());

        return cacheRepository.save(entity);
    }

    /**
     * 获取 FinOps 真实对账统计
     */
    public Map<String, Object> getFinOpsCacheStats() {
        long totalEntries = cacheRepository.count();
        long totalHits = cacheRepository.sumTotalHits();
        long totalSavedTokens = cacheRepository.sumTotalSavedTokens();

        // 估算节省的算力费用 (以通义 qwen-plus 计价：平均输入 ¥0.004/1k, 输出 ¥0.012/1k，折合约 ¥0.01/1k tokens)
        double estimatedSavedCny = Math.round((totalSavedTokens * 0.00001) * 100.0) / 100.0;

        List<PromptHashCacheEntity> topEntries = cacheRepository.findTop20ByOrderByHitCountDescLastHitAtDesc();
        List<Map<String, Object>> entryList = new ArrayList<>();

        long totalSavedMs = 0L;
        for (PromptHashCacheEntity e : topEntries) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("hashKey", e.getHashKey());
            item.put("shortHash", e.getHashKey().substring(0, 10));
            item.put("rawPrompt", e.getRawPrompt());
            item.put("normalizedPrompt", e.getNormalizedPrompt());
            item.put("originalTokens", e.getOriginalTokens());
            item.put("originalDurationMs", e.getOriginalDurationMs());
            item.put("hitCount", e.getHitCount());
            item.put("totalSavedTokens", e.getTotalSavedTokens());
            item.put("lastHitAt", e.getLastHitAt() != null ? e.getLastHitAt().toString() : "");
            entryList.add(item);

            long savedPerHit = Math.max(0, (e.getOriginalDurationMs() != null ? e.getOriginalDurationMs() : 1000) - 2);
            totalSavedMs += savedPerHit * e.getHitCount();
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalCachedPrompts", totalEntries);
        stats.put("totalHits", totalHits);
        stats.put("totalSavedTokens", totalSavedTokens);
        stats.put("estimatedSavedCny", estimatedSavedCny);
        stats.put("isEstimatedBilling", true);
        stats.put("billingNotice", "基于通义/DeepSeek标准公有云刊例价估算(约¥0.01/1k tk)，非第三方云服务商真实账单账期接口");
        stats.put("totalSavedLatencySeconds", Math.round(totalSavedMs / 1000.0 * 10.0) / 10.0);
        stats.put("topEntries", entryList);
        stats.put("timestamp", LocalDateTime.now().toString());

        return stats;
    }

    /**
     * 清空缓存 (方便测试人员与面试官重新对比无缓存状态)
     */
    @Transactional
    public void clearAllCache() {
        cacheRepository.deleteAll();
    }
}

