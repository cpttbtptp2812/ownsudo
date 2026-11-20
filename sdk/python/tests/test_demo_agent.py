from __future__ import annotations

import json
import sys
import threading
from pathlib import Path
from types import SimpleNamespace

import httpx

sys.path.insert(0, str(Path(__file__).parents[1] / "examples"))
import demo_agent

from ownagent import LocalWorker, OwnAgentClient


class TraceUnavailableAgent:
    def create_trace_span(self, span: dict) -> None:
        raise RuntimeError("trace service unavailable")

    def create_action(self, tool_name: str, arguments: dict, **kwargs: object) -> SimpleNamespace:
        return SimpleNamespace(
            action_id="action-1",
            decision="ALLOW",
            status="READY",
        )

    def wait_for_terminal(self, action_id: str, **kwargs: object) -> SimpleNamespace:
        return SimpleNamespace(
            action_id=action_id,
            status="SUCCEEDED",
            output={"matches": [{"id": "runtime"}]},
        )


def test_trace_ingestion_failure_does_not_break_successful_agent_run(
    monkeypatch,
) -> None:
    monkeypatch.setenv("OPENAI_MODEL", "test-model")
    responses = [
        {
            "model": "test-model",
            "choices": [
                {
                    "message": {
                        "role": "assistant",
                        "tool_calls": [
                            {
                                "id": "call-1",
                                "type": "function",
                                "function": {
                                    "name": demo_agent.TOOL_NAME,
                                    "arguments": '{"query": "runtime"}',
                                },
                            }
                        ],
                    }
                }
            ],
            "usage": {
                "prompt_tokens": 10,
                "completion_tokens": 5,
                "total_tokens": 15,
            },
        },
        {
            "model": "test-model",
            "choices": [{"message": {"role": "assistant", "content": "Task completed."}}],
            "usage": {
                "prompt_tokens": 20,
                "completion_tokens": 8,
                "total_tokens": 28,
            },
        },
    ]

    def handler(request: httpx.Request) -> httpx.Response:
        payload = responses.pop(0)
        return httpx.Response(200, json=payload)

    with httpx.Client(
        base_url="https://provider.example/v1/",
        transport=httpx.MockTransport(handler),
    ) as llm:
        result = demo_agent.run_agent(
            llm,
            TraceUnavailableAgent(),
            "Find runtime information.",
            "trace-1",
        )

    assert result == "Task completed."
    assert responses == []



class InMemoryActionRuntime:
    """Small HTTP-level Action Runtime used to test the real SDK/Worker protocol."""

    def __init__(self) -> None:
        self.lock = threading.RLock()
        self.tool_registered = False
        self.action_status = None
        self.action_output = None
        self.action_arguments = None
        self.spans: list[dict] = []

    def handle(self, request: httpx.Request) -> httpx.Response:
        path = request.url.path
        with self.lock:
            if path == "/api/v1/tools" and request.method == "POST":
                assert request.headers["X-OwnAgent-Tool-Admin-Key"] == "developer-key"
                payload = json.loads(request.content)
                assert payload["toolName"] == demo_agent.TOOL_NAME
                assert payload["riskLevel"] == "READ_ONLY"
                self.tool_registered = True
                return httpx.Response(201, json={"toolName": demo_agent.TOOL_NAME})

            if path == "/api/v1/actions" and request.method == "POST":
                assert self.tool_registered
                payload = json.loads(request.content)
                assert payload["toolName"] == demo_agent.TOOL_NAME
                self.action_arguments = payload["arguments"]
                self.action_status = "READY"
                return httpx.Response(201, json=self._action())

            if path == "/api/v1/worker/actions/claim" and request.method == "POST":
                payload = json.loads(request.content)
                if self.action_status == "READY" and demo_agent.TOOL_NAME in payload["toolNames"]:
                    self.action_status = "RUNNING"
                    return httpx.Response(200, json={
                        "actionId": "action-1",
                        "toolName": demo_agent.TOOL_NAME,
                        "arguments": self.action_arguments,
                        "leaseToken": "lease-1",
                        "attemptCount": 1,
                    })
                return httpx.Response(204)

            if path == "/api/v1/worker/actions/action-1/heartbeat" and request.method == "POST":
                assert json.loads(request.content)["leaseToken"] == "lease-1"
                return httpx.Response(200, json={"actionId": "action-1", "leaseUntil": "later"})

            if path == "/api/v1/worker/actions/action-1/complete" and request.method == "POST":
                payload = json.loads(request.content)
                assert payload["leaseToken"] == "lease-1"
                self.action_output = payload["output"]
                self.action_status = "SUCCEEDED"
                return httpx.Response(200, json={"actionId": "action-1", "status": "SUCCEEDED"})

            if path == "/api/v1/actions/action-1" and request.method == "GET":
                return httpx.Response(200, json=self._action())

            if path == "/api/v1/traces/spans" and request.method == "POST":
                self.spans.append(json.loads(request.content))
                return httpx.Response(202, json={"accepted": True})

            return httpx.Response(404, json={"error": {"message": f"Unexpected request: {request.method} {path}"}})

    def _action(self) -> dict:
        return {
            "actionId": "action-1",
            "agentId": "agent-1",
            "toolName": demo_agent.TOOL_NAME,
            "status": self.action_status or "READY",
            "decision": "ALLOW",
            "riskLevel": "READ_ONLY",
            "arguments": self.action_arguments or {},
            "metadata": {},
            "output": self.action_output,
            "errorCode": None,
            "errorMessage": None,
            "attemptCount": 1 if self.action_status in {"RUNNING", "SUCCEEDED"} else 0,
        }


def test_model_agent_action_worker_and_tool_result_complete_one_business_flow(monkeypatch) -> None:
    monkeypatch.setenv("OPENAI_MODEL", "test-model")
    runtime = InMemoryActionRuntime()
    model_requests: list[dict] = []

    def model_handler(request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.content)
        model_requests.append(payload)
        messages = payload["messages"]
        if not any(message.get("role") == "tool" for message in messages):
            reply = {
                "role": "assistant",
                "tool_calls": [{
                    "id": "call-1",
                    "type": "function",
                    "function": {
                        "name": demo_agent.TOOL_NAME,
                        "arguments": '{"query":"runtime"}',
                    },
                }],
            }
        else:
            tool_result = json.loads(messages[-1]["content"])
            assert tool_result["matches"][0]["id"] == "runtime"
            reply = {
                "role": "assistant",
                "content": "OwnAgent Action Runtime accepted the request, the local Worker searched the workspace, and returned the runtime evidence.",
            }
        return httpx.Response(200, json={
            "model": "test-model",
            "choices": [{"message": reply}],
            "usage": {"prompt_tokens": 12, "completion_tokens": 8, "total_tokens": 20},
        })

    stop_worker = threading.Event()
    with OwnAgentClient(
        "http://ownagent.test",
        "agent-key",
        tool_admin_api_key="developer-key",
        transport=httpx.MockTransport(runtime.handle),
    ) as client:
        client.register_tool(
            demo_agent.TOOL_NAME,
            "Search the local example workspace (read-only)",
            {
                "type": "object",
                "properties": {"query": {"type": "string"}},
                "required": ["query"],
                "additionalProperties": False,
            },
            risk_level="READ_ONLY",
        )
        worker = LocalWorker(client, poll_interval=0.01, heartbeat_interval=10)
        worker.register(demo_agent.TOOL_NAME, demo_agent.search_workspace)
        worker_thread = threading.Thread(
            target=worker.run_forever,
            args=(stop_worker,),
            daemon=True,
        )
        worker_thread.start()
        try:
            with httpx.Client(
                base_url="https://provider.example/v1/",
                transport=httpx.MockTransport(model_handler),
            ) as llm:
                answer = demo_agent.run_agent(
                    llm,
                    client,
                    "Find the OwnAgent runtime note.",
                    "trace-main-business-flow",
                )
        finally:
            stop_worker.set()
            worker_thread.join(timeout=2)

    assert "local Worker searched the workspace" in answer
    assert runtime.action_status == "SUCCEEDED"
    assert runtime.action_output["matches"][0]["id"] == "runtime"
    assert runtime.action_arguments == {"query": "runtime"}
    assert any(message.get("role") == "tool" for message in model_requests[-1]["messages"])
    assert len(runtime.spans) >= 3
    assert any(span["name"] == "action.execute" and span["status"] == "OK" for span in runtime.spans)



def test_parse_task_accepts_custom_prompt() -> None:
    task = "Inspect the Action Runtime and explain its lifecycle."
    assert demo_agent.parse_task([task]) == task


def test_parse_task_uses_documented_default() -> None:
    assert demo_agent.parse_task([]) == demo_agent.DEFAULT_TASK


def test_parse_task_rejects_blank_prompt() -> None:
    import pytest

    with pytest.raises(SystemExit) as error:
        demo_agent.parse_task(["   "])
    assert error.value.code == 2
