"""Exceptions raised by the OwnAgent SDK."""
from __future__ import annotations

from typing import Any


class OwnAgentError(Exception):
    """Base exception for SDK errors."""


class OwnAgentAPIError(OwnAgentError):
    """An HTTP error returned by the OwnAgent API."""

    def __init__(self, status_code: int, message: str, code: str | None = None,
                 payload: dict[str, Any] | None = None) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.code = code
        self.payload = payload or {}


class OwnAgentTimeoutError(OwnAgentError):
    """The SDK stopped waiting; the remote Action may still be running."""
