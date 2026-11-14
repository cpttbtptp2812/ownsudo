from __future__ import annotations

from typing import Any

from ownagent.worker import LocalWorker


class FakeClient:
    def __init__(self, claim: dict[str, Any] | None) -> None:
        self.claim = claim
        self.completed: list[tuple[str, str, dict[str, Any]]] = []
        self.unknown: list[tuple[str, str, str, str]] = []
        self.heartbeats = 0

    def claim_worker_action(self, tool_names: list[str]) -> dict[str, Any] | None:
        assert "demo.add" in tool_names
        claim, self.claim = self.claim, None
        return claim

    def heartbeat_worker_action(self, action_id: str, lease_token: str) -> dict[str, Any]:
        self.heartbeats += 1
        return {"actionId": action_id}

    def complete_worker_action(
        self, action_id: str, lease_token: str, output: dict[str, Any]
    ) -> dict[str, Any]:
        self.completed.append((action_id, lease_token, output))
        return {}

    def mark_worker_action_unknown(
        self, action_id: str, lease_token: str, error_code: str, error_message: str
    ) -> dict[str, Any]:
        self.unknown.append((action_id, lease_token, error_code, error_message))
        return {}


def sample_claim() -> dict[str, Any]:
    return {
        "actionId": "act-123",
        "toolName": "demo.add",
        "arguments": {"left": 2, "right": 3},
        "leaseToken": "lease-secret",
        "attemptCount": 1,
    }


def test_worker_executes_registered_handler_and_completes_action() -> None:
    client = FakeClient(sample_claim())
    worker = LocalWorker(client, heartbeat_interval=10)
    worker.register("demo.add", lambda args: {"sum": args["left"] + args["right"]})

    assert worker.run_once() is True
    assert client.completed == [("act-123", "lease-secret", {"sum": 5})]
    assert client.unknown == []


def test_worker_marks_handler_exception_unknown_instead_of_false_failure() -> None:
    client = FakeClient(sample_claim())
    worker = LocalWorker(client)

    def broken_handler(_: dict[str, Any]) -> dict[str, Any]:
        raise RuntimeError("remote API may have partially applied the change")

    worker.register("demo.add", broken_handler)
    assert worker.run_once() is True
    assert client.completed == []
    assert client.unknown[0][0:3] == (
        "act-123", "lease-secret", "WORKER_HANDLER_EXCEPTION"
    )


def test_worker_returns_false_when_no_action_is_available() -> None:
    client = FakeClient(None)
    worker = LocalWorker(client)
    worker.register("demo.add", lambda _: {"ok": True})

    assert worker.run_once() is False


def test_worker_requires_a_registered_tool() -> None:
    client = FakeClient(None)
    worker = LocalWorker(client)

    try:
        worker.run_once()
    except RuntimeError as exc:
        assert "register at least one" in str(exc)
    else:
        raise AssertionError("expected RuntimeError")


def test_worker_rejects_duplicate_and_invalid_tool_registration() -> None:
    client = FakeClient(None)
    worker = LocalWorker(client)
    worker.register("demo.add", lambda _: {"ok": True})
    try:
        worker.register("demo.add", lambda _: {"ok": True})
    except ValueError as exc:
        assert "already registered" in str(exc)
    else:
        raise AssertionError("expected duplicate registration to fail")

    try:
        worker.register("", lambda _: {"ok": True})
    except ValueError as exc:
        assert "1-128" in str(exc)
    else:
        raise AssertionError("expected empty tool name to fail")


def test_worker_sends_heartbeat_while_handler_is_running() -> None:
    import time

    client = FakeClient(sample_claim())
    worker = LocalWorker(client, heartbeat_interval=0.01)
    worker.register("demo.add", lambda _: (time.sleep(0.05) or {"ok": True}))

    assert worker.run_once() is True
    assert client.heartbeats >= 1


def test_worker_marks_claim_for_missing_local_handler_unknown() -> None:
    client = FakeClient(sample_claim())
    worker = LocalWorker(client)
    worker.register("demo.add", lambda _: {"ok": True})
    original = client.claim_worker_action

    def mismatched_claim(tool_names: list[str]) -> dict[str, Any]:
        original(tool_names)
        claim = sample_claim()
        claim["toolName"] = "demo.uninstalled"
        return claim

    client.claim_worker_action = mismatched_claim  # type: ignore[method-assign]
    assert worker.run_once() is True
    assert client.unknown[0][2] == "WORKER_HANDLER_MISSING"


def test_worker_rejects_non_dict_handler_output_as_unknown() -> None:
    client = FakeClient(sample_claim())
    worker = LocalWorker(client)
    worker.register("demo.add", lambda _: "not-a-dict")  # type: ignore[arg-type]

    assert worker.run_once() is True
    assert client.unknown[0][2] == "WORKER_HANDLER_EXCEPTION"
