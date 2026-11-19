package com.ownsudo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "chat_agent_run_evaluation", indexes =
        @Index(name = "idx_chat_run_eval_history", columnList = "run_id, created_at"))
public class ChatAgentRunEvaluationEntity {
    @Id
    @Column(name = "evaluation_id", length = 64)
    private String evaluationId;
    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;
    @Column(name = "judge_model", nullable = false, length = 128)
    private String judgeModel;
    @Column(columnDefinition = "TEXT")
    private String rubric;
    @Column(name = "scores_json", nullable = false, columnDefinition = "LONGTEXT")
    private String scoresJson;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected ChatAgentRunEvaluationEntity() {}
}