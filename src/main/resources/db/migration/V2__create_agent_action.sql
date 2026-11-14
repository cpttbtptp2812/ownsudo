CREATE TABLE IF NOT EXISTS agent_action (
    id VARCHAR(64) NOT NULL,
    agent_id VARCHAR(64) NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    arguments_json LONGTEXT NOT NULL,
    metadata_json LONGTEXT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    decision VARCHAR(32) NOT NULL,
    risk_level VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_action_agent_idempotency UNIQUE (agent_id, idempotency_key),
    INDEX idx_action_agent_created (agent_id, created_at),
    CONSTRAINT fk_agent_action_agent
        FOREIGN KEY (agent_id) REFERENCES agent_definition(id)
);
