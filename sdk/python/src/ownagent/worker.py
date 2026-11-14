"""Local Python Worker for executing developer-owned tool handlers."""

from __future__ import annotations

import logging
import threading
import time
from collections.abc import Callable
from typing import Any

from .client import OwnAgentClient
from .errors import OwnAgentError

logger = logging.getLogger(__name__)
ToolHandler = Callable[[dict[str, Any]], dict[str, Any]]


class LocalWorker:
    """Poll OwnAgent and execute only explicitly registered local Python functions.

    The Worker uses the Agent API key and can only claim Actions belonging to that Agent.
    Handler exceptions are marked UNKNOWN because a partial external side effect may have
    happened before the exception was raised. The Worker never retries an Action itself.
    """

    def __init__(
        self,
        client: OwnAgentClient,
        *,
        poll_interval: float = 1.0,
        heartbeat_interval: float = 15.0,
    ) -> None:
        if poll_interval <= 0 or heartbeat_interval <= 0:
            raise ValueError("poll_interval and heartbeat_interval must be positive")
        self._client = client
        self._poll_interval = poll_interval
        self._heartbeat_interval = heartbeat_interval
        self._handlers: dict[str, ToolHandler] = {}

    def register(self, tool_name: str, handler: ToolHandler) -> None:
        if not tool_name or len(tool_name) > 128:
            raise ValueError("tool_name must contain 1-128 characters")
        if tool_name in self._handlers:
            raise ValueError(f"handler already registered for {tool_name}")
        self._handlers[tool_name] = handler

    @property
    def tool_names(self) -> tuple[str, ...]:
        return tuple(self._handlers)

    def run_once(self) -> bool:
        """Execute at most one available Action. Returns False when the queue is empty."""
        if not self._handlers:
            raise RuntimeError("register at least one local tool before starting the Worker")
        claim = self._client.claim_worker_action(list(self._handlers))
        if claim is None:
            return False

        action_id = claim["actionId"]
        tool_name = claim["toolName"]
        lease_token = claim["leaseToken"]
        arguments = claim.get("arguments") or {}
        handler = self._handlers.get(tool_name)
        if handler is None:
            self._client.mark_worker_action_unknown(
                action_id, lease_token, "WORKER_HANDLER_MISSING",
                f"Worker claimed {tool_name} but has no matching local handler",
            )
            return True

        stop_heartbeat = threading.Event()
        heartbeat = threading.Thread(
            target=self._heartbeat_loop,
            args=(action_id, lease_token, stop_heartbeat),
            name=f"ownagent-heartbeat-{action_id}",
            daemon=True,
        )
        heartbeat.start()
        try:
            output = handler(arguments)
            if not isinstance(output, dict):
                raise TypeError("A local tool handler must return a dict")
        except Exception as exc:
            stop_heartbeat.set()
            heartbeat.join(timeout=max(1.0, self._heartbeat_interval))
            try:
                self._client.mark_worker_action_unknown(
                    action_id,
                    lease_token,
                    "WORKER_HANDLER_EXCEPTION",
                    f"{type(exc).__name__}: {exc}"[:1000],
                )
            except OwnAgentError:
                logger.exception("Could not persist UNKNOWN for Action %s", action_id)
            return True
        else:
            stop_heartbeat.set()
            heartbeat.join(timeout=max(1.0, self._heartbeat_interval))
            try:
                self._client.complete_worker_action(action_id, lease_token, output)
            except OwnAgentError:
                # The completion request may have reached the server. Do not repeat it;
                # lease recovery will mark the Action UNKNOWN if its final state is absent.
                logger.exception("Could not confirm completion for Action %s", action_id)
            return True

    def run_forever(self, stop_event: threading.Event | None = None) -> None:
        """Poll until stop_event is set. An idle Worker sleeps between polls."""
        stop = stop_event or threading.Event()
        while not stop.is_set():
            try:
                claimed = self.run_once()
            except OwnAgentError:
                logger.exception("OwnAgent Worker poll failed")
                claimed = False
            if not claimed:
                stop.wait(self._poll_interval)

    def _heartbeat_loop(
        self, action_id: str, lease_token: str, stop_event: threading.Event
    ) -> None:
        while not stop_event.wait(self._heartbeat_interval):
            try:
                self._client.heartbeat_worker_action(action_id, lease_token)
            except OwnAgentError:
                # The lease may have been lost. The final write is fenced by the server.
                logger.exception("Heartbeat failed for Action %s", action_id)
                return
