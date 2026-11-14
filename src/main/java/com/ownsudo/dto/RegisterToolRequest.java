package com.ownsudo.dto;

import java.util.Map;

public class RegisterToolRequest {
    private String toolName;
    private String description;
    private Map<String, Object> inputSchema;
    private String riskLevel;
    private Boolean enabled;
    public String getToolName(){return toolName;} public void setToolName(String v){toolName=v;}
    public String getDescription(){return description;} public void setDescription(String v){description=v;}
    public Map<String,Object> getInputSchema(){return inputSchema;} public void setInputSchema(Map<String,Object> v){inputSchema=v;}
    public String getRiskLevel(){return riskLevel;} public void setRiskLevel(String v){riskLevel=v;}
    public Boolean getEnabled(){return enabled;} public void setEnabled(Boolean v){enabled=v;}
}