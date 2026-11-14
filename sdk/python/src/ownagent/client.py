"""Synchronous OwnAgent API client.

The SDK never retries Action execution automatically. A network timeout after an execute
request is ambiguous: the server may have performed the external side effect.
"""
from __future__ import annotations

import time
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
        transport: httpx.BaseTransport | None = None,
    ) -> None:
        if not base_url.strip():
            raise ValueError("base_url must not be empty")
        if not api_key.strip():
            raise ValueError("api_key must not be empty")
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
