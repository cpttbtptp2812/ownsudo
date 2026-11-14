ALTER TABLE agent_action
    ADD COLUMN approval_status VARCHAR(16) NOT NULL DEFAULT 'NOT_REQUIRED',
    ADD COLUMN approval_decided_by VARCHAR(128) NULL,
    ADD COLUMN approval_decided_at DATETIME NULL,
    ADD COLUMN approval_reason VARCHAR(1000) NULL;

UPDATE agent_action
SET approval_status = 'PENDING'
WHERE decision = 'REQUIRE_APPROVAL' AND status = 'PENDING_APPROVAL';

CREATE INDEX idx_action_agent_approval
    ON agent_action (agent_id, approval_status, created_at);

CREATE INDEX idx_action_worker_claim
    ON agent_action (agent_id, status, tool_name, created_at);
