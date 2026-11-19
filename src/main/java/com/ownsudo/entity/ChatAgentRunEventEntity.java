package com.ownsudo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "chat_agent_run_event", uniqueConstraints =
        @UniqueConstraint(name = "uk_chat_run_event_sequence", columnNames = {"run_id", "sequence_no"}),
        indexes = @Index(name = "idx_chat_run_event_timeline", columnList = "run_id, event_time"))
public class ChatAgentRunEventEntity {
    @Id
    @Column(name = "event_id", length = 64)
    private String eventId;
    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;
    @Column(name = "sequence_no", nullable = false)
    private Long sequenceNo;
    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;
    @Column(nullable = false, length = 24)
    private String status;
    @Column(name = "event_time", nullable = false)
    private LocalDateTime eventTime;
    @Column(name = "payload_json", columnDefinition = "LONGTEXT")
    private String payloadJson;

    protected ChatAgentRunEventEntity() {}
}