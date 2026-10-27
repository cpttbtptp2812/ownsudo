package com.ownsudo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "mcp_tool")
public class McpTool {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "server_name", length = 64)
    private String serverName;

    @Column(name = "function_name", length = 64)
    private String functionName;

    @Column(name = "display_name", length = 128)
    private String displayName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 64)
    private String category;

    @Column(name = "risk_level", length = 32)
    private String riskLevel; // LOW, MEDIUM, HIGH, CRITICAL

    @Column(name = "is_safe")
    private Boolean isSafe = true;

    @Column(name = "timeout_ms")
    private Integer timeoutMs = 5000;

    @Column(columnDefinition = "LONGTEXT")
    private String parametersSchema;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public McpTool() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getServerName() { return serverName; }
    public void setServerName(String serverName) { this.serverName = serverName; }

    public String getFunctionName() { return functionName; }
    public void setFunctionName(String functionName) { this.functionName = functionName; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }

    public Boolean getIsSafe() { return isSafe; }
    public void setIsSafe(Boolean isSafe) { this.isSafe = isSafe; }

    public Integer getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(Integer timeoutMs) { this.timeoutMs = timeoutMs; }

    public String getParametersSchema() { return parametersSchema; }
    public void setParametersSchema(String parametersSchema) { this.parametersSchema = parametersSchema; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}