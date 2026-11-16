from __future__ import annotations

import sys
from pathlib import Path
from types import SimpleNamespace

import httpx

sys.path.insert(0, str(Path(__file__).parents[1] / "examples"))
import demo_agent  # noqa: E402


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
