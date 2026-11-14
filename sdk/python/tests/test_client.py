from __future__ import annotations

import json

import httpx
import pytest

from ownagent import OwnAgentAPIError, OwnAgentClient, OwnAgentTimeoutError


def action_payload(status: str = "READY") -> dict:
    return {
        "actionId": "act-123",
        "agentId": "agent-1",
        "toolName": "demo.echo",
        "status": status,
        "decision": "ALLOW",
        "riskLevel": "LOW",
        "arguments": {"message": "hello"},
        "metadata": None,
        "output": {"message": "hello"} if status == "SUCCEEDED" else None,
        "errorCode": None,
        "errorMessage": None,
        "attemptCount": 1,
    }


def test_create_action_sends_bearer_and_idempotency_key() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.method == "POST"
        assert request.url.path == "/api/v1/actions"
        assert request.headers["Authorization"] == "Bearer test-key"
        body = json.loads(request.content)
        assert body["toolName"] == "demo.echo"
        assert body["idempotencyKey"] == "stable-key"
        return httpx.Response(201, json=action_payload())

    with OwnAgentClient("http://localhost:8080", "test-key",
                        transport=httpx.MockTransport(handler)) as client:
        action = client.create_action("demo.echo", {"message": "hello"},
                                      idempotency_key="stable-key")
    assert action.action_id == "act-123"
    assert action.status == "READY"


def test_execute_action_posts_once_and_returns_succeeded() -> None:
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        assert request.method == "POST"
        assert request.url.path == "/api/v1/actions/act-123/execute"
        return httpx.Response(200, json=action_payload("SUCCEEDED"))

    with OwnAgentClient("http://localhost:8080", "test-key",
                        transport=httpx.MockTransport(handler)) as client:
        result = client.execute_action("act-123")
    assert result.status == "SUCCEEDED"
    assert calls == 1


def test_api_error_preserves_code_and_status() -> None:
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(409, json={"error": {
            "code": "ACTION_STATE_CONFLICT", "message": "Action cannot execute"
        }})

    with (
        OwnAgentClient("http://localhost:8080", "test-key",
                       transport=httpx.MockTransport(handler)) as client,
        pytest.raises(OwnAgentAPIError) as error,
    ):
        client.execute_action("act-123")
    assert error.value.status_code == 409
    assert error.value.code == "ACTION_STATE_CONFLICT"


def test_unknown_is_a_terminal_result_not_retried() -> None:
    calls = 0

    def handler(_: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(200, json=action_payload("UNKNOWN"))

    with OwnAgentClient("http://localhost:8080", "test-key",
                        transport=httpx.MockTransport(handler)) as client:
        result = client.wait_for_terminal("act-123", timeout=1, poll_interval=0.01)
    assert result.status == "UNKNOWN"
    assert calls == 1


def test_timeout_is_explicitly_ambiguous() -> None:
    def handler(_: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("simulated timeout")

    with (
        OwnAgentClient("http://localhost:8080", "test-key",
                       transport=httpx.MockTransport(handler)) as client,
        pytest.raises(OwnAgentTimeoutError, match="may have completed"),
    ):
        client.execute_action("act-123")


def test_rejects_empty_idempotency_key() -> None:
    with (
        OwnAgentClient("http://localhost:8080", "test-key",
                       transport=httpx.MockTransport(lambda _: httpx.Response(200, json={}))) as client,
        pytest.raises(ValueError),
    ):
        client.create_action("demo.echo", {}, idempotency_key="")
