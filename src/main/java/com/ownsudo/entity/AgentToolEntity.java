package com.ownsudo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "agent_tool", uniqueConstraints = @UniqueConstraint(
        name = "uk_agent_tool_name", columnNames = {"agent_id", "tool_name"}))
public class AgentToolEntity {
    @Id @Column(length = 64) private String id;
    @Column(name = "agent_id", nullable = false, length = 64) private String agentId;
    @Column(name = "tool_name", nullable = false, length = 128) private String toolName;
    @Column(nullable = false, length = 512) private String description;
    @Column(name = "input_schema_json", nullable = false, columnDefinition = "LONGTEXT") private String inputSchemaJson;
    @Column(name = "risk_level", nullable = false, length = 24) private String riskLevel;
    @Column(nullable = false) private boolean enabled;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
    @PrePersist void onCreate() { LocalDateTime now=LocalDateTime.now(); if(createdAt==null)createdAt=now; if(updatedAt==null)updatedAt=now; }
    @PreUpdate void onUpdate() { updatedAt=LocalDateTime.now(); }
    public String getId(){return id;} public void setId(String v){id=v;}
    public String getAgentId(){return agentId;} public void setAgentId(String v){agentId=v;}
    public String getToolName(){return toolName;} public void setToolName(String v){toolName=v;}
    public String getDescription(){return description;} public void setDescription(String v){description=v;}
    public String getInputSchemaJson(){return inputSchemaJson;} public void setInputSchemaJson(String v){inputSchemaJson=v;}
    public String getRiskLevel(){return riskLevel;} public void setRiskLevel(String v){riskLevel=v;}
    public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}
    public LocalDateTime getCreatedAt(){return createdAt;}
}