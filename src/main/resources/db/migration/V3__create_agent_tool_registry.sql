CREATE TABLE IF NOT EXISTS agent_tool (
    id VARCHAR(64) NOT NULL,
    agent_id VARCHAR(64) NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    description VARCHAR(512) NOT NULL,
    input_schema_json LONGTEXT NOT NULL,
    risk_level VARCHAR(24) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_tool_name UNIQUE (agent_id, tool_name),
    INDEX idx_agent_tool_agent (agent_id),
    CONSTRAINT fk_agent_tool_agent FOREIGN KEY (agent_id) REFERENCES agent_definition(id)
);
