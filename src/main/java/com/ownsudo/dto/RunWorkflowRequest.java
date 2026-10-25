package com.ownsudo.dto;

public class RunWorkflowRequest {
    private String workflowId;
    private String triggeredBy = "admin";
    private String inputParams;

    public RunWorkflowRequest() {}

    public String getWorkflowId() { return workflowId; }
    public void setWorkflowId(String workflowId) { this.workflowId = workflowId; }

    public String getTriggeredBy() { return triggeredBy; }
    public void setTriggeredBy(String triggeredBy) { this.triggeredBy = triggeredBy; }

    public String getInputParams() { return inputParams; }
    public void setInputParams(String inputParams) { this.inputParams = inputParams; }
}
