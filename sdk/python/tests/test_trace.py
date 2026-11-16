from __future__ import annotations

import json

import httpx
import pytest

from ownagent import OwnAgentClient


def test_context_manager_reports_open_and_final_span_with_provider_usage() -> None:
    requests: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/api/v1/traces/spans"
        requests.append(json.loads(request.content))
        return httpx.Response(202, json={"accepted": True})

    with OwnAgentClient("http://localhost:8080", "test-key",
                        transport=httpx.MockTransport(handler)) as client:
        with client.trace_span("trace-1", "agent.run", run_id="run-1") as root:
            with client.trace_span("trace-1", "llm.call") as llm:
                llm.set_usage(input_tokens=10, output_tokens=4)

    assert root.span_id
    assert len(requests) == 4
    assert requests[0]["status"] == "UNSET" and requests[0]["endedAt"] is None
    assert requests[1]["parentSpanId"] == requests[0]["spanId"]
    assert requests[2]["status"] == "OK"
    assert requests[2]["usageSource"] == "PROVIDER"
    assert requests[2]["totalTokens"] is None
    assert requests[3]["status"] == "OK"


def test_context_manager_records_error_and_reraises() -> None:
    requests: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(json.loads(request.content))
        return httpx.Response(202, json={"accepted": True})

    with OwnAgentClient("http://localhost:8080", "test-key",
                        transport=httpx.MockTransport(handler)) as client:
        with pytest.raises(RuntimeError, match="boom"):
            with client.trace_span("trace-2", "tool.call") as span:
                span.attributes["tool"] = "demo.echo"
                raise RuntimeError("boom")

    assert [request["status"] for request in requests] == ["UNSET", "ERROR"]
    assert requests[-1]["attributes"]["error_type"] == "RuntimeError"


def test_trace_context_best_effort_does_not_replace_agent_result() -> None:
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(503, json={"error": {"message": "telemetry offline"}})

    with OwnAgentClient("http://localhost:8080", "test-key",
                        transport=httpx.MockTransport(handler)) as client:
        with client.trace_span("trace-3", "agent.run", best_effort=True):
            answer = "Agent result remains available"

    assert answer == "Agent result remains available"
