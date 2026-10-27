package com.ownsudo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 精确哈希降本缓存实体 (Exact Hash Cache)
 * 记录规范化 Prompt 的 SHA-256 签名与其真实模型生成产物
 * 相同问题二次触发时 10-15ms 极速返回，上游 Token 消耗为 0
 */
@Entity
@Table(name = "prompt_hash_cache", indexes = {
    @Index(name = "idx_hash_key", columnList = "hash_key", unique = true)
})
public class PromptHashCacheEntity {

    @Id
    @Column(name = "hash_key", length = 64)
    private String hashKey; // 规范化后的 SHA-256 十六进制摘要

    @Column(name = "normalized_prompt", columnDefinition = "TEXT")
    private String normalizedPrompt;

    @Column(name = "raw_prompt", columnDefinition = "TEXT")
    private String rawPrompt;

    @Column(columnDefinition = "LONGTEXT")
    private String content;

    @Column(columnDefinition = "TEXT")
    private String reasoning;

    @Column(name = "citations_json", columnDefinition = "TEXT")
    private String citationsJson;

    @Column(name = "original_tokens")
    private Integer originalTokens = 0; // 首次真实生成时上游实际返回的 Token 消耗

    @Column(name = "original_duration_ms")
    private Long originalDurationMs = 0L; // 首次真实生成时的真实耗时 (毫秒)

    @Column(name = "hit_count")
    private Integer hitCount = 0; // 累计命中拦截次数

    @Column(name = "total_saved_tokens")
    private Long totalSavedTokens = 0L; // 累计节省的真实 Token 总数

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "last_hit_at")
    private LocalDateTime lastHitAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (lastHitAt == null) lastHitAt = LocalDateTime.now();
        if (hitCount == null) hitCount = 0;
        if (totalSavedTokens == null) totalSavedTokens = 0L;
    }

    public PromptHashCacheEntity() {}

    public String getHashKey() { return hashKey; }
    public void setHashKey(String hashKey) { this.hashKey = hashKey; }

    public String getNormalizedPrompt() { return normalizedPrompt; }
    public void setNormalizedPrompt(String normalizedPrompt) { this.normalizedPrompt = normalizedPrompt; }

    public String getRawPrompt() { return rawPrompt; }
    public void setRawPrompt(String rawPrompt) { this.rawPrompt = rawPrompt; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getReasoning() { return reasoning; }
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }

    public String getCitationsJson() { return citationsJson; }
    public void setCitationsJson(String citationsJson) { this.citationsJson = citationsJson; }

    public Integer getOriginalTokens() { return originalTokens; }
    public void setOriginalTokens(Integer originalTokens) { this.originalTokens = originalTokens; }

    public Long getOriginalDurationMs() { return originalDurationMs; }
    public void setOriginalDurationMs(Long originalDurationMs) { this.originalDurationMs = originalDurationMs; }

    public Integer getHitCount() { return hitCount; }
    public void setHitCount(Integer hitCount) { this.hitCount = hitCount; }

    public Long getTotalSavedTokens() { return totalSavedTokens; }
    public void setTotalSavedTokens(Long totalSavedTokens) { this.totalSavedTokens = totalSavedTokens; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getLastHitAt() { return lastHitAt; }
    public void setLastHitAt(LocalDateTime lastHitAt) { this.lastHitAt = lastHitAt; }
}

