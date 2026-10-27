package com.ownsudo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "agent_definition")
public class AgentEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 64)
    private String avatar;

    @Column(length = 64)
    private String model;

    @Column(nullable = false)
    private Double temperature = 0.70;

    @Column(columnDefinition = "LONGTEXT")
    private String systemPrompt;

    @Column(length = 32)
    private String status = "PUBLISHED";

    @Column(name = "tools_count")
    private Integer toolsCount = 0;

    @Column(name = "kb_count")
    private Integer kbCount = 0;

    @Column(name = "enable_memory")
    private Boolean enableMemory = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public AgentEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getToolsCount() { return toolsCount != null ? toolsCount : 0; }
    public void setToolsCount(Integer toolsCount) { this.toolsCount = toolsCount; }

    public Integer getKbCount() { return kbCount != null ? kbCount : 0; }
    public void setKbCount(Integer kbCount) { this.kbCount = kbCount; }

    public Boolean getEnableMemory() { return enableMemory != null ? enableMemory : true; }
    public void setEnableMemory(Boolean enableMemory) { this.enableMemory = enableMemory; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
