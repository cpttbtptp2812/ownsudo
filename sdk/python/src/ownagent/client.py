"""Synchronous OwnAgent API client.

The SDK never retries Action execution automatically. A network timeout after an execute
request is ambiguous: the server may have performed the external side effect.
"""
from __future__ import annotations

import json
import time
from collections.abc import Iterator
from typing import Any

import httpx

from .errors import OwnAgentAPIError, OwnAgentTimeoutError
from .models import Action, ActionEvent


class OwnAgentClient:
    def __init__(
        self,
        base_url: str,
        api_key: str,
        *,
        timeout: float = 15.0,
        tool_admin_api_key: str | None = None,
        approver_api_key: str | None = None,
        transport: httpx.BaseTransport | None = None,
    ) -> None:
        if not base_url.strip():
            raise ValueError("base_url must not be empty")
        if not api_key.strip():
            raise ValueError("api_key must not be empty")
        self._tool_admin_api_key = tool_admin_api_key
        self._approver_api_key = approver_api_key
        self._client = httpx.Client(
            base_url=base_url.rstrip("/"),
            headers={"Authorization": f"Bearer {api_key}", "Accept": "application/json"},
            timeout=timeout,
            transport=transport,
        )

    def __enter__(self) -> OwnAgentClient:
        return self

    def __exit__(self, *_: object) -> None:
        self.close()

    def close(self) -> None:
        self._client.close()

    def register_tool(
        self,
        tool_name: str,
        description: str,
        input_schema: dict[str, Any],
        *,
        risk_level: str = "LOW",
        enabled: bool = True,
    ) -> dict[str, Any]:
        """Register tool metadata. Requires the separate developer Tool Admin key."""
        if not self._tool_admin_api_key:
            raise ValueError("tool_admin_api_key is required to register tool metadata")
        if not tool_name.strip() or not description.strip():
            raise ValueError("tool_name and description must not be empty")
        return self._request(
            "POST",
            "/api/v1/tools",
            headers={"X-OwnAgent-Tool-Admin-Key": self._tool_admin_api_key},
            json={
                "toolName": tool_name,
                "description": description,
                "inputSchema": input_schema,
                "riskLevel": risk_level,
                "enabled": enabled,
            },
        )

    def create_action(
        self,
        tool_name: str,
        arguments: dict[str, Any],
        *,
        idempotency_key: str,
        metadata: dict[str, Any] | None = None,
    ) -> Action:
        if not tool_name.strip():
            raise ValueError("tool_name must not be empty")
        if not idempotency_key.strip():
            raise ValueError("idempotency_key must not be empty")
        body: dict[str, Any] = {
            "toolName": tool_name,
            "arguments": arguments,
            "idempotencyKey": idempotency_key,
        }
        if metadata is not None:
            body["metadata"] = metadata
        return Action.from_dict(self._request("POST", "/api/v1/actions", json=body))

    def retry_failed_action(
        self,
        action_id: str,
        *,
        acknowledge_possible_duplicate_side_effect: bool = False,
    ) -> Action:
        """Create a new Action from a FAILED attempt after explicit risk acknowledgement.

        UNKNOWN Actions are never retryable through this method; reconcile the
        external system first. The new Action is re-evaluated by server policy.
        """
        self._require_id(action_id)
        if not acknowledge_possible_duplicate_side_effect:
            raise ValueError("Explicit acknowledgement of possible duplicate side effects is required")
        return Action.from_dict(self._request(
            "POST",
            f"/api/v1/actions/{action_id}/retry",
            params={"confirmPossibleDuplicateSideEffect": "true"},
        ))

    def execute_action(self, action_id: str) -> Action:
        """Execute once. Do not blindly retry after a transport error."""
        self._require_id(action_id)
        return Action.from_dict(
            self._request("POST", f"/api/v1/actions/{action_id}/execute")
        )

    def get_action(self, action_id: str) -> Action:
        self._require_id(action_id)
        return Action.from_dict(self._request("GET", f"/api/v1/actions/{action_id}"))

    def list_actions(self, *, limit: int = 50) -> list[Action]:
        if not 1 <= limit <= 200:
            raise ValueError("limit must be between 1 and 200")
        data = self._request("GET", "/api/v1/actions", params={"limit": limit})
        return [Action.from_dict(item) for item in data]

    def get_events(self, action_id: str) -> list[ActionEvent]:
        self._require_id(action_id)
        data = self._request("GET", f"/api/v1/actions/{action_id}/events")
        return [ActionEvent.from_dict(item) for item in data]

    def wait_for_terminal(
        self,
        action_id: str,
        *,
        timeout: float = 60.0,
        poll_interval: float = 1.0,
    ) -> Action:
        """Poll persisted state; UNKNOWN is terminal and must be reconciled before retry."""
        self._require_id(action_id)
        if timeout <= 0 or poll_interval <= 0:
            raise ValueError("timeout and poll_interval must be positive")
        deadline = time.monotonic() + timeout
        terminal = {"SUCCEEDED", "FAILED", "DENIED", "UNKNOWN"}
        while True:
            action = self.get_action(action_id)
            if action.status in terminal:
                return action
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise OwnAgentTimeoutError(
                    f"Stopped waiting for Action {action_id}; its remote execution may continue"
                )
            time.sleep(min(poll_interval, remaining))

    def list_pending_approvals(self) -> list[Action]:
        """List pending approvals for this Agent; requires a separate approver key."""
        if not self._approver_api_key:
            raise ValueError("approver_api_key is required to list pending approvals")
        data = self._request(
            "GET", "/api/v1/actions/pending-approval",
            headers={"X-OwnAgent-Approver-Key": self._approver_api_key},
        )
        return [Action.from_dict(item) for item in data]

    def approve_action(self, action_id: str, reason: str | None = None) -> Action:
        return self._decide_action_approval(action_id, "approve", reason)

    def reject_action(self, action_id: str, reason: str | None = None) -> Action:
        return self._decide_action_approval(action_id, "reject", reason)

    def _decide_action_approval(
        self, action_id: str, decision: str, reason: str | None
    ) -> Action:
        self._require_id(action_id)
        if not self._approver_api_key:
            raise ValueError("approver_api_key is required to decide Action approvals")
        body = {"reason": reason} if reason is not None else {}
        result = self._request(
            "POST", f"/api/v1/actions/{action_id}/{decision}",
            headers={"X-OwnAgent-Approver-Key": self._approver_api_key},
            json=body,
        )
        return Action.from_dict(result)

    def claim_worker_action(self, tool_names: list[str]) -> dict[str, Any] | None:
        """Claim one READY Action for a locally installed tool; returns None when idle."""
        if not tool_names or len(tool_names) > 100:
            raise ValueError("tool_names must contain between 1 and 100 names")
        result = self._request(
            "POST", "/api/v1/worker/actions/claim", json={"toolNames": tool_names}
        )
        return result if isinstance(result, dict) else None

    def heartbeat_worker_action(self, action_id: str, lease_token: str) -> dict[str, Any]:
        self._require_id(action_id)
        return self._request(
            "POST", f"/api/v1/worker/actions/{action_id}/heartbeat",
            json={"leaseToken": lease_token},
        )

    def complete_worker_action(
        self, action_id: str, lease_token: str, output: dict[str, Any]
    ) -> dict[str, Any]:
        self._require_id(action_id)
        return self._request(
            "POST", f"/api/v1/worker/actions/{action_id}/complete",
            json={"leaseToken": lease_token, "output": output},
        )

    def fail_worker_action(
        self, action_id: str, lease_token: str, error_code: str, error_message: str
    ) -> dict[str, Any]:
        self._require_id(action_id)
        return self._request(
            "POST", f"/api/v1/worker/actions/{action_id}/fail",
            json={
                "leaseToken": lease_token,
                "errorCode": error_code,
                "errorMessage": error_message,
            },
        )

    def mark_worker_action_unknown(
        self, action_id: str, lease_token: str, error_code: str, error_message: str
    ) -> dict[str, Any]:
        """Record uncertainty when a local handler may have caused a partial side effect."""
        self._require_id(action_id)
        return self._request(
            "POST", f"/api/v1/worker/actions/{action_id}/unknown",
            json={
                "leaseToken": lease_token,
                "errorCode": error_code,
                "errorMessage": error_message,
            },
        )

    @staticmethod
    def _require_id(action_id: str) -> None:
        if not action_id or "/" in action_id:
            raise ValueError("action_id must be a non-empty path-safe identifier")

    def _request(self, method: str, path: str, **kwargs: Any) -> Any:
        try:
            response = self._client.request(method, path, **kwargs)
        except httpx.TimeoutException as exc:
            raise OwnAgentTimeoutError(
                "OwnAgent request timed out; the remote operation may have completed"
            ) from exc
        try:
            response.raise_for_status()
        except httpx.HTTPStatusError as exc:
            try:
                payload = response.json()
            except ValueError:
                payload = {"message": response.text}
            error = payload.get("error", {}) if isinstance(payload, dict) else {}
            raise OwnAgentAPIError(
                response.status_code,
                error.get("message") or f"OwnAgent API returned HTTP {response.status_code}",
                error.get("code"),
                payload if isinstance(payload, dict) else {},
            ) from exc
        if response.status_code == 204 or not response.content:
            return None
        return response.json()



    def trace_span(
        self,
        trace_id: str,
        name: str,
        *,
        run_id: str | None = None,
        span_id: str | None = None,
        parent_span_id: str | None = None,
        span_kind: str = "INTERNAL",
        attributes: dict[str, Any] | None = None,
        best_effort: bool = True,
    ):
        """Create a context-managed span; nested spans inherit their parent ID."""
        from .trace import TraceSpan

        return TraceSpan(
            self, trace_id, name, run_id=run_id, span_id=span_id,
            parent_span_id=parent_span_id, span_kind=span_kind,
            attributes=attributes, best_effort=best_effort,
        )

    def create_trace_span(self, span: dict[str, Any]) -> dict[str, Any]:
        """Persist one span. Token counts must come from provider-reported usage."""
        required = ("trace_id", "span_id", "name", "started_at")
        missing = [key for key in required if not span.get(key)]
        if missing:
            raise ValueError(f"trace span is missing required fields: {', '.join(missing)}")
        body = {
            "traceId": span["trace_id"], "spanId": span["span_id"],
            "parentSpanId": span.get("parent_span_id"), "runId": span.get("run_id"),
            "name": span["name"], "spanKind": span.get("span_kind", "INTERNAL"),
            "status": span.get("status", "UNSET"), "startedAt": span["started_at"],
            "endedAt": span.get("ended_at"), "inputTokens": span.get("input_tokens"),
            "outputTokens": span.get("output_tokens"), "totalTokens": span.get("total_tokens"),
            "usageSource": span.get("usage_source", "MISSING"), "attributes": span.get("attributes"),
        }
        return self._request("POST", "/api/v1/traces/spans", json=body)

    def get_trace_spans(self, trace_id: str) -> list[dict[str, Any]]:
        """Return spans in start-time order for the authenticated Agent."""
        self._require_id(trace_id)
        return self._request("GET", f"/api/v1/traces/{trace_id}/spans")


    def stream_trace_events(self, trace_id: str) -> Iterator[dict[str, Any]]:
        """Yield live span events for a trace.

        Load existing spans with get_trace_spans() before subscribing so the
        persisted API remains the source of truth. This stream only carries
        updates published after the subscription is established.
        """
        self._require_id(trace_id)
        try:
            with self._client.stream(
                "GET",
                f"/api/v1/traces/{trace_id}/events",
                headers={"Accept": "text/event-stream"},
                timeout=None,
            ) as response:
                try:
                    response.raise_for_status()
                except httpx.HTTPStatusError as exc:
                    payload: dict[str, Any]
                    try:
                        payload = response.json()
                    except (ValueError, json.JSONDecodeError):
                        payload = {"message": "OwnAgent trace event stream request failed"}
                    error = payload.get("error", {}) if isinstance(payload, dict) else {}
                    raise OwnAgentAPIError(
                        response.status_code,
                        error.get("message") or f"OwnAgent API returned HTTP {response.status_code}",
                        error.get("code"),
                        payload if isinstance(payload, dict) else {},
                    ) from exc

                event_name = "message"
                event_id: str | None = None
                data_lines: list[str] = []
                for line in response.iter_lines():
                    if line == "":
                        if data_lines:
                            raw_data = "\\n".join(data_lines)
                            try:
                                data: Any = json.loads(raw_data)
                            except json.JSONDecodeError:
                                data = raw_data
                            yield {"event": event_name, "id": event_id, "data": data}
                        event_name = "message"
                        event_id = None
                        data_lines = []
                    elif line.startswith(":"):
                        continue
                    elif line.startswith("event:"):
                        event_name = line[6:].strip() or "message"
                    elif line.startswith("id:"):
                        event_id = line[3:].strip()
                    elif line.startswith("data:"):
                        value = line[5:]
                        data_lines.append(value.removeprefix(" "))
        except httpx.TimeoutException as exc:
            raise OwnAgentTimeoutError("Timed out while reading the OwnAgent trace event stream") from exc
