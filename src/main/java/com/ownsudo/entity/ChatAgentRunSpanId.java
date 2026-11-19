package com.ownsudo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class ChatAgentRunSpanId implements Serializable {
    @Column(name = "run_id", length = 64)
    private String runId;
    @Column(name = "span_id", length = 64)
    private String spanId;

    public ChatAgentRunSpanId() {}
    public ChatAgentRunSpanId(String runId, String spanId) { this.runId = runId; this.spanId = spanId; }
    public String getRunId() { return runId; }
    public String getSpanId() { return spanId; }
    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ChatAgentRunSpanId that)) return false;
        return Objects.equals(runId, that.runId) && Objects.equals(spanId, that.spanId);
    }
    @Override public int hashCode() { return Objects.hash(runId, spanId); }
}