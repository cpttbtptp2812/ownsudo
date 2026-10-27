package com.ownsudo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "agent_skill")
public class AgentSkill {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 64)
    private String category; // FINANCE, SECOPS, CUSTOMER_SERVICE, OPS

    @Column(length = 64)
    private String badge;

    @Column(name = "badge_type", length = 32)
    private String badgeType; // finance, ticket, schedule, secops

    @Column(length = 255)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "suggested_query", length = 255)
    private String suggestedQuery;

    @Column(name = "tags_json", length = 512)
    private String tagsJson;

    @Column(name = "workflow_def_id", length = 64)
    private String workflowDefId;

    @Column(columnDefinition = "LONGTEXT")
    private String promptTemplate;

    @Column(length = 32)
    private String version;

    @Column(nullable = false)
    private Boolean enabled = true;

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

    public AgentSkill() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getBadge() { return badge; }
    public void setBadge(String badge) { this.badge = badge; }

    public String getBadgeType() { return badgeType; }
    public void setBadgeType(String badgeType) { this.badgeType = badgeType; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getSuggestedQuery() { return suggestedQuery; }
    public void setSuggestedQuery(String suggestedQuery) { this.suggestedQuery = suggestedQuery; }

    public String getTagsJson() { return tagsJson; }
    public void setTagsJson(String tagsJson) { this.tagsJson = tagsJson; }

    public String getWorkflowDefId() { return workflowDefId; }
    public void setWorkflowDefId(String workflowDefId) { this.workflowDefId = workflowDefId; }

    public String getPromptTemplate() { return promptTemplate; }
    public void setPromptTemplate(String promptTemplate) { this.promptTemplate = promptTemplate; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}