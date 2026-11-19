"""Small synchronous tracing helper for instrumenting external Python Agents."""
from __future__ import annotations

import logging
from contextvars import ContextVar, Token
from datetime import datetime, timezone
from typing import Any
from uuid import uuid4

from .client import OwnAgentClient

logger = logging.getLogger(__name__)
_active_span: ContextVar[tuple[str, str, int, str | None] | None] = ContextVar("ownagent_active_span", default=None)


def _timestamp() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


class TraceSpan:
    """Context manager that reports span start and completion to OwnAgent.

    Token usage is never inferred. Call set_usage() with values returned by the
    model provider. By default telemetry errors are logged and do not replace the
    Agent's result; set best_effort=False when telemetry delivery is mandatory.
    """

    def __init__(
        self,
        client: OwnAgentClient,
        trace_id: str,
        name: str,
        *,
        run_id: str | None = None,
        span_id: str | None = None,
        parent_span_id: str | None = None,
        span_kind: str = "INTERNAL",
        attributes: dict[str, Any] | None = None,
        best_effort: bool = True,
    ) -> None:
        if not trace_id or "/" in trace_id:
            raise ValueError("trace_id must be a non-empty path-safe identifier")
        if not name.strip():
            raise ValueError("name must not be empty")
        self.client = client
        self.trace_id = trace_id
        self.run_id = run_id or (active[3] if active and active[0] == trace_id and active[2] == id(client) else None)
        self.span_id = span_id or uuid4().hex
        active = _active_span.get()
        self.parent_span_id = parent_span_id or (
            active[1] if active and active[0] == trace_id and active[2] == id(client) else None
        )
        self.name = name
        self.span_kind = span_kind
        self.attributes = dict(attributes or {})
        self.best_effort = best_effort
        self.started_at = _timestamp()
        self.input_tokens: int | None = None
        self.output_tokens: int | None = None
        self.total_tokens: int | None = None
        self.usage_source = "MISSING"
        self._context_token: Token[tuple[str, str, int, str | None] | None] | None = None

    def set_usage(
        self,
        *,
        input_tokens: int | None = None,
        output_tokens: int | None = None,
        total_tokens: int | None = None,
        source: str = "PROVIDER",
    ) -> None:
        """Set provider-reported usage; do not pass estimates as PROVIDER."""
        for name, value in (
            ("input_tokens", input_tokens),
            ("output_tokens", output_tokens),
            ("total_tokens", total_tokens),
        ):
            if value is not None and value < 0:
                raise ValueError(f"{name} must be non-negative")
        normalized = source.upper()
        if normalized not in {"PROVIDER", "MISSING", "ESTIMATED"}:
            raise ValueError("source must be PROVIDER, MISSING, or ESTIMATED")
        if normalized == "PROVIDER" and all(
            value is None for value in (input_tokens, output_tokens, total_tokens)
        ):
            raise ValueError("PROVIDER usage requires at least one token count")
        self.input_tokens = input_tokens
        self.output_tokens = output_tokens
        self.total_tokens = total_tokens
        self.usage_source = normalized

    def _send(self, status: str, ended_at: str | None = None) -> None:
        payload = {
            "trace_id": self.trace_id,
            "span_id": self.span_id,
            "parent_span_id": self.parent_span_id,
            "run_id": self.run_id,
            "name": self.name,
            "span_kind": self.span_kind,
            "status": status,
            "started_at": self.started_at,
            "ended_at": ended_at,
            "input_tokens": self.input_tokens,
            "output_tokens": self.output_tokens,
            "total_tokens": self.total_tokens,
            "usage_source": self.usage_source,
            "attributes": self.attributes or None,
        }
        try:
            self.client.create_trace_span(payload)
        except Exception:
            if not self.best_effort:
                raise
            logger.warning("OwnAgent trace span delivery failed for %s", self.name, exc_info=True)

    def __enter__(self) -> TraceSpan:
        self._context_token = _active_span.set((self.trace_id, self.span_id, id(self.client), self.run_id))
        try:
            self._send("UNSET")
        except Exception:
            _active_span.reset(self._context_token)
            self._context_token = None
            raise
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        traceback: object,
    ) -> bool:
        try:
            if exc is not None:
                self.attributes.setdefault("error_type", exc_type.__name__ if exc_type else "Exception")
            self._send("ERROR" if exc is not None else "OK", _timestamp())
        finally:
            if self._context_token is not None:
                _active_span.reset(self._context_token)
        return False


class AgentRun:
    """A complete Agent execution with a generated trace/run identity.

    Nested spans created through run.span() inherit the same run_id and parent
    span context. Session identity is stored as an explicit span attribute.
    """

    def __init__(
        self,
        client: OwnAgentClient,
        name: str,
        *,
        session_id: str | None = None,
        attributes: dict[str, Any] | None = None,
        best_effort: bool = True,
    ) -> None:
        if not name.strip():
            raise ValueError("name must not be empty")
        self.client = client
        self.trace_id = uuid4().hex
        self.run_id = uuid4().hex
        root_attributes = dict(attributes or {})
        if session_id:
            root_attributes.setdefault("session.id", session_id)
        root_attributes.setdefault("ownagent.run.id", self.run_id)
        self._root = TraceSpan(
            client,
            self.trace_id,
            name,
            run_id=self.run_id,
            attributes=root_attributes,
            best_effort=best_effort,
        )

    def __enter__(self) -> AgentRun:
        self._root.__enter__()
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        traceback: object,
    ) -> bool:
        return self._root.__exit__(exc_type, exc, traceback)

    def span(
        self,
        name: str,
        *,
        span_kind: str = "INTERNAL",
        attributes: dict[str, Any] | None = None,
        best_effort: bool = True,
    ) -> TraceSpan:
        """Create a child span associated with this run."""
        return self.client.trace_span(
            self.trace_id,
            name,
            run_id=self.run_id,
            span_kind=span_kind,
            attributes=attributes,
            best_effort=best_effort,
        )
