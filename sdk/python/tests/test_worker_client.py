from __future__ import annotations

import json

import httpx
import pytest

from ownagent import OwnAgentClient, OwnAgentTimeoutError


def test_register_tool_uses_separate_developer_key() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.method == "POST"
        assert request.url.path == "/api/v1/tools"
        assert request.headers["Authorization"] == "Bearer agent-key"
        assert request.headers["X-OwnAgent-Tool-Admin-Key"] == "developer-key"
        body = json.loads(request.content)
        assert body["toolName"] == "demo.local_greeting"
        assert body["riskLevel"] == "LOW"
        return httpx.Response(201, json={"toolName": "demo.local_greeting"})

    with OwnAgentClient(
        "http://localhost:8080",
        "agent-key",
        tool_admin_api_key="developer-key",
        transport=httpx.MockTransport(handler),
    ) as client:
        result = client.register_tool(
            "demo.local_greeting",
            "Greeting",
            {"type": "object", "properties": {}, "additionalProperties": False},
        )

    assert result["toolName"] == "demo.local_greeting"


def test_register_tool_requires_developer_key() -> None:
    with (
        OwnAgentClient("http://localhost:8080", "agent-key") as client,
        pytest.raises(ValueError, match="tool_admin_api_key"),
    ):
        client.register_tool("demo.tool", "Tool", {"type": "object"})


def test_worker_protocol_methods_use_lease_token() -> None:
    seen: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request.url.path)
        if request.url.path.endswith("/claim"):
            assert json.loads(request.content)["toolNames"] == ["demo.local"]
            return httpx.Response(200, json={
                "actionId": "act-1", "toolName": "demo.local",
                "arguments": {}, "leaseToken": "lease-1",
            })
        body = json.loads(request.content)
        assert body["leaseToken"] == "lease-1"
        return httpx.Response(200, json={"ok": True})

    with OwnAgentClient(
        "http://localhost:8080", "agent-key",
        transport=httpx.MockTransport(handler),
    ) as client:
        claim = client.claim_worker_action(["demo.local"])
        assert claim and claim["leaseToken"] == "lease-1"
        client.heartbeat_worker_action("act-1", "lease-1")
        client.complete_worker_action("act-1", "lease-1", {"result": "ok"})
        client.fail_worker_action("act-1", "lease-1", "TOOL_FAILED", "known failure")
        client.mark_worker_action_unknown("act-1", "lease-1", "UNCERTAIN", "unknown outcome")

    assert seen == [
        "/api/v1/worker/actions/claim",
        "/api/v1/worker/actions/act-1/heartbeat",
        "/api/v1/worker/actions/act-1/complete",
        "/api/v1/worker/actions/act-1/fail",
        "/api/v1/worker/actions/act-1/unknown",
    ]


def test_worker_claim_returns_none_when_idle_and_validates_tool_list() -> None:
    with OwnAgentClient(
        "http://localhost:8080", "agent-key",
        transport=httpx.MockTransport(lambda _: httpx.Response(204)),
    ) as client:
        assert client.claim_worker_action(["demo.local"]) is None
        with pytest.raises(ValueError):
            client.claim_worker_action([])


def test_client_rejects_unsafe_action_id() -> None:
    with (
        OwnAgentClient("http://localhost:8080", "agent-key") as client,
        pytest.raises(ValueError),
    ):
        client.get_action("act/../other")


def test_wait_for_terminal_times_out_without_retrying_execution() -> None:
    calls = 0

    def handler(_: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(200, json={
            "actionId": "act-123", "agentId": "agent-1", "toolName": "demo.echo",
            "status": "RUNNING", "decision": "ALLOW", "arguments": {},
            "attemptCount": 1,
        })

    with (
        OwnAgentClient("http://localhost:8080", "agent-key",
                       transport=httpx.MockTransport(handler)) as client,
        pytest.raises(OwnAgentTimeoutError, match="Stopped waiting"),
    ):
        client.wait_for_terminal("act-123", timeout=0.02, poll_interval=0.01)

    assert calls >= 1


def test_approval_client_uses_separate_approver_key() -> None:
    seen: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["Authorization"] == "Bearer agent-key"
        assert request.headers["X-OwnAgent-Approver-Key"] == "approver-key"
        seen.append(request.method + " " + request.url.path)
        action = {
            "actionId": "act-approval",
            "agentId": "agent-1",
            "toolName": "demo.sensitive",
            "status": "READY",
            "decision": "REQUIRE_APPROVAL",
            "riskLevel": "HIGH",
            "arguments": {"target": "prod"},
            "approvalStatus": "APPROVED",
            "approvalDecidedBy": "operator-1",
            "approvalReason": "Reviewed",
            "attemptCount": 0,
        }
        if request.url.path.endswith("pending-approval"):
            return httpx.Response(200, json=[action])
        return httpx.Response(200, json=action)

    with OwnAgentClient(
        "http://localhost:8080", "agent-key",
        approver_api_key="approver-key",
        transport=httpx.MockTransport(handler),
    ) as client:
        pending = client.list_pending_approvals()
        approved = client.approve_action("act-approval", "Reviewed")
        rejected = client.reject_action("act-approval", "Unsafe")

    assert pending[0].approval_status == "APPROVED"
    assert approved.approval_decided_by == "operator-1"
    assert rejected.approval_reason == "Reviewed"
    assert seen == [
        "GET /api/v1/actions/pending-approval",
        "POST /api/v1/actions/act-approval/approve",
        "POST /api/v1/actions/act-approval/reject",
    ]


def test_approval_methods_require_separate_approver_key() -> None:
    with OwnAgentClient("http://localhost:8080", "agent-key") as client:
        with pytest.raises(ValueError, match="approver_api_key"):
            client.list_pending_approvals()
        with pytest.raises(ValueError, match="approver_api_key"):
            client.approve_action("act-1")
