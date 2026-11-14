CREATE TABLE IF NOT EXISTS agent_api_key (
    id VARCHAR(64) NOT NULL,
    agent_id VARCHAR(64) NOT NULL,
    key_name VARCHAR(100) NOT NULL,
    key_hash CHAR(64) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL,
    last_used_at DATETIME NULL,
    revoked_at DATETIME NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_api_key_hash UNIQUE (key_hash),
    CONSTRAINT uk_agent_api_key_agent_name UNIQUE (agent_id, key_name),
    INDEX idx_agent_api_key_agent_id (agent_id),
    CONSTRAINT fk_agent_api_key_agent
        FOREIGN KEY (agent_id) REFERENCES agent_definition(id)
);
