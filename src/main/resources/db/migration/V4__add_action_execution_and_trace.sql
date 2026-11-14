ALTER TABLE agent_action
    ADD COLUMN output_json LONGTEXT NULL,
    ADD COLUMN error_code VARCHAR(64) NULL,
    ADD COLUMN error_message VARCHAR(1000) NULL,
    ADD COLUMN attempt_count INT NOT NULL DEFAULT 0,
    ADD COLUMN started_at DATETIME NULL,
    ADD COLUMN completed_at DATETIME NULL,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS agent_action_event (
    id VARCHAR(64) NOT NULL,
    agent_id VARCHAR(64) NOT NULL,
    action_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    detail_json LONGTEXT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_action_event_agent_action (agent_id, action_id, created_at),
    CONSTRAINT fk_action_event_agent FOREIGN KEY (agent_id) REFERENCES agent_definition(id),
    CONSTRAINT fk_action_event_action FOREIGN KEY (action_id) REFERENCES agent_action(id)
);
