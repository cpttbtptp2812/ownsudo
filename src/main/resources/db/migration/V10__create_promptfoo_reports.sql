CREATE TABLE agent_promptfoo_report (
    report_id VARCHAR(36) NOT NULL,
    agent_id VARCHAR(64) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    report_format VARCHAR(8) NOT NULL,
    result_count INT NOT NULL,
    success_count INT NOT NULL,
    failure_count INT NOT NULL,
    error_count INT NOT NULL,
    report_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (report_id),
    INDEX idx_agent_promptfoo_reports (agent_id, created_at),
    CONSTRAINT fk_agent_promptfoo_report_agent FOREIGN KEY (agent_id)
        REFERENCES agent_definition(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
