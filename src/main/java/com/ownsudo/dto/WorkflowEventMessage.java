package com.ownsudo.dto;

import java.time.LocalDateTime;

public class WorkflowEventMessage {
    private String eventType; // INSTANCE_STARTED, NODE_RUNNING, NODE_COMPLETED, TICKET_REQUIRED, TICKET_APPROVED, INSTANCE_COMPLETED, INSTANCE_FAILED
    private String instanceId;
    private String nodeId;
    private String nodeName;
    private String nodeType;
    private String status;
    private Object payload;
    private String timestamp = LocalDateTime.now().toString();

    public WorkflowEventMessage() {}

    public WorkflowEventMessage(String eventType, String instanceId, String nodeId, String status, Object payload) {
        this.eventType = eventType;
        this.instanceId = instanceId;
        this.nodeId = nodeId;
        this.status = status;
        this.payload = payload;
        this.timestamp = LocalDateTime.now().toString();
    }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public String getNodeName() { return nodeName; }
    public void setNodeName(String nodeName) { this.nodeName = nodeName; }

    public String getNodeType() { return nodeType; }
    public void setNodeType(String nodeType) { this.nodeType = nodeType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Object getPayload() { return payload; }
    public void setPayload(Object payload) { this.payload = payload; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
}
