package com.ownsudo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "agent_memory")
public class AgentMemory {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "memory_type", length = 32)
    private String memoryType; // WORKING, EPISODIC, SEMANTIC

    @Column(columnDefinition = "LONGTEXT")
    private String content;

    @Column(name = "importance_score")
    private Double importanceScore = 1.0;

    @Column(name = "reference_entity", length = 128)
    private String referenceEntity;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "last_accessed_at")
    private LocalDateTime lastAccessedAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (lastAccessedAt == null) lastAccessedAt = LocalDateTime.now();
    }

    public AgentMemory() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getMemoryType() { return memoryType; }
    public void setMemoryType(String memoryType) { this.memoryType = memoryType; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Double getImportanceScore() { return importanceScore; }
    public void setImportanceScore(Double importanceScore) { this.importanceScore = importanceScore; }

    public String getReferenceEntity() { return referenceEntity; }
    public void setReferenceEntity(String referenceEntity) { this.referenceEntity = referenceEntity; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getLastAccessedAt() { return lastAccessedAt; }
    public void setLastAccessedAt(LocalDateTime lastAccessedAt) { this.lastAccessedAt = lastAccessedAt; }
}