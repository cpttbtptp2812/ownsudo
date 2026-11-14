"""Typed response models returned by the OwnAgent API."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True, slots=True)
class Action:
    action_id: str
    agent_id: str
    tool_name: str
    status: str
    decision: str
    risk_level: str | None
    arguments: dict[str, Any]
    metadata: dict[str, Any] | None
    output: dict[str, Any] | None
    error_code: str | None
    error_message: str | None
    attempt_count: int
    created_at: str | None = None
    updated_at: str | None = None
    started_at: str | None = None
    completed_at: str | None = None
    approval_status: str | None = None
    approval_decided_by: str | None = None
    approval_decided_at: str | None = None
    approval_reason: str | None = None

    @classmethod
    def from_dict(cls, value: dict[str, Any]) -> Action:
        return cls(
            action_id=value["actionId"],
            agent_id=value["agentId"],
            tool_name=value["toolName"],
            status=value["status"],
            decision=value["decision"],
            risk_level=value.get("riskLevel"),
            arguments=value.get("arguments") or {},
            metadata=value.get("metadata"),
            output=value.get("output"),
            error_code=value.get("errorCode"),
            error_message=value.get("errorMessage"),
            attempt_count=int(value.get("attemptCount") or 0),
            created_at=value.get("createdAt"),
            updated_at=value.get("updatedAt"),
            started_at=value.get("startedAt"),
            completed_at=value.get("completedAt"),
            approval_status=value.get("approvalStatus"),
            approval_decided_by=value.get("approvalDecidedBy"),
            approval_decided_at=value.get("approvalDecidedAt"),
            approval_reason=value.get("approvalReason"),
        )


@dataclass(frozen=True, slots=True)
class ActionEvent:
    event_id: str
    action_id: str
    event_type: str
    details: dict[str, Any] | None
    created_at: str | None = None

    @classmethod
    def from_dict(cls, value: dict[str, Any]) -> ActionEvent:
        return cls(
            event_id=value["eventId"],
            action_id=value["actionId"],
            event_type=value["eventType"],
            details=value.get("details"),
            created_at=value.get("createdAt"),
        )
