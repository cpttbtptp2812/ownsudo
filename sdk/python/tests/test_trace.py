from __future__ import annotations

import json

import httpx

from ownagent import OwnAgentClient


def test_context_manager_reports_open_and_final_span_with_provider_usage() -> None:
    requests: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/api/v1/traces/spans"
        requests.append(json.loads(request.content))
        return httpx.Response(202, json={"accepted": True})

    client = OwnAgentClient("http://localhost:8080", "test-key",
                            transport=httpx.MockTransport(handler))
    try:
        with client.trace_span("trace-1", "agent.run", run_id="run-1") as root:
            root.attributes["root"] = True
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
    finally:
        client.close()


def test_context_manager_records_error_and_reraises() -> None:
    requests: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(json.loads(request.content))
        return httpx.Response(202, json={"accepted": True})

    client = OwnAgentClient("http://localhost:8080", "test-key",
                            transport=httpx.MockTransport(handler))
    try:
        try:
            with client.trace_span("trace-2", "tool.call") as span:
                span.attributes["tool"] = "demo.echo"
                raise RuntimeError("boom")
        except RuntimeError as error:
            assert str(error) == "boom"
        else:
            raise AssertionError("Agent exception should be re-raised")

        assert [request["status"] for request in requests] == ["UNSET", "ERROR"]
        assert requests[-1]["attributes"]["error_type"] == "RuntimeError"
    finally:
        client.close()


def test_trace_context_best_effort_does_not_replace_agent_result() -> None:
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(503, json={"error": {"message": "telemetry offline"}})

    client = OwnAgentClient("http://localhost:8080", "test-key",
                            transport=httpx.MockTransport(handler))
    try:
        with client.trace_span("trace-3", "agent.run", best_effort=True):
            answer = "Agent result remains available"
        assert answer == "Agent result remains available"
    finally:
        client.close()


def test_agent_run_propagates_run_and_session_identity_to_nested_spans() -> None:
    requests: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(json.loads(request.content))
        return httpx.Response(202, json={"accepted": True})

    with OwnAgentClient("http://localhost:8080", "test-key",
                        transport=httpx.MockTransport(handler)) as client:
        with client.agent_run("customer-support", session_id="session-42") as run:
            with run.span("llm.chat.completions", span_kind="CLIENT") as llm:
                llm.set_usage(input_tokens=21, output_tokens=8)
            with run.span("tool.lookup_order") as tool:
                tool.attributes["tool_name"] = "lookup_order"

    assert len(requests) == 6
    assert requests[0]["runId"] == run.run_id
    assert requests[1]["runId"] == run.run_id
    assert requests[1]["parentSpanId"] == requests[0]["spanId"]
    assert requests[3]["runId"] == run.run_id
    assert requests[3]["parentSpanId"] == requests[0]["spanId"]
    assert requests[-1]["runId"] == run.run_id
    assert requests[0]["attributes"]["session.id"] == "session-42"
