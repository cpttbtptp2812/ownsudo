CREATE TABLE chat_agent_run (
    run_id VARCHAR(64) NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    agent_id VARCHAR(64) NULL,
    name VARCHAR(200) NOT NULL,
    model VARCHAR(128) NULL,
    provider VARCHAR(128) NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'RUNNING',
    started_at DATETIME(6) NOT NULL,
    ended_at DATETIME(6) NULL,
    duration_ms BIGINT NULL,
    input_tokens BIGINT NULL,
    output_tokens BIGINT NULL,
    total_tokens BIGINT NULL,
    usage_source VARCHAR(24) NOT NULL DEFAULT 'MISSING',
    error_message VARCHAR(2000) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (run_id),
    UNIQUE KEY uk_chat_run_trace (trace_id),
    INDEX idx_chat_run_session (session_id, started_at),
    INDEX idx_chat_run_status (status, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE chat_agent_run_event (
    event_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    sequence_no BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    event_time DATETIME(6) NOT NULL,
    payload_json LONGTEXT NULL,
    PRIMARY KEY (event_id),
    UNIQUE KEY uk_chat_run_event_sequence (run_id, sequence_no),
    INDEX idx_chat_run_event_timeline (run_id, event_time),
    CONSTRAINT fk_chat_run_event_run FOREIGN KEY (run_id)
        REFERENCES chat_agent_run(run_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE chat_agent_run_span (
    run_id VARCHAR(64) NOT NULL,
    span_id VARCHAR(64) NOT NULL,
    parent_span_id VARCHAR(64) NULL,
    name VARCHAR(200) NOT NULL,
    span_kind VARCHAR(32) NOT NULL DEFAULT 'INTERNAL',
    status VARCHAR(24) NOT NULL DEFAULT 'UNSET',
    started_at DATETIME(6) NOT NULL,
    ended_at DATETIME(6) NULL,
    duration_ms BIGINT NULL,
    input_tokens BIGINT NULL,
    output_tokens BIGINT NULL,
    usage_source VARCHAR(24) NOT NULL DEFAULT 'MISSING',
    attributes_json LONGTEXT NULL,
    PRIMARY KEY (run_id, span_id),
    INDEX idx_chat_run_span_order (run_id, started_at),
    CONSTRAINT fk_chat_run_span_run FOREIGN KEY (run_id)
        REFERENCES chat_agent_run(run_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE chat_agent_run_evaluation (
    evaluation_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    judge_model VARCHAR(128) NOT NULL,
    rubric TEXT NULL,
    scores_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (evaluation_id),
    INDEX idx_chat_run_eval_history (run_id, created_at),
    CONSTRAINT fk_chat_run_eval_run FOREIGN KEY (run_id)
        REFERENCES chat_agent_run(run_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
