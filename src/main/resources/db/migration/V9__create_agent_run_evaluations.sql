CREATE TABLE agent_run_evaluation (
    evaluation_id VARCHAR(36) NOT NULL,
    agent_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NULL,
    trace_id VARCHAR(64) NOT NULL,
    judge_model VARCHAR(255) NOT NULL,
    rubric TEXT NULL,
    scores_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (evaluation_id),
    INDEX idx_agent_trace_evaluation (agent_id, trace_id, created_at),
    INDEX idx_agent_run_evaluation (agent_id, run_id, created_at),
    CONSTRAINT fk_agent_run_evaluation_agent FOREIGN KEY (agent_id)
        REFERENCES agent_definition(id),
    CONSTRAINT fk_agent_run_evaluation_run FOREIGN KEY (agent_id, run_id)
        REFERENCES agent_run(agent_id, run_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
