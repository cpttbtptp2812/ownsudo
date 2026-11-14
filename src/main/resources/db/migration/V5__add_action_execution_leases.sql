ALTER TABLE agent_action
    ADD COLUMN lease_token VARCHAR(36) NULL,
    ADD COLUMN lease_until DATETIME NULL;

CREATE INDEX idx_action_status_lease
    ON agent_action (status, lease_until);
