"""Runnable example: a model-driven Agent using OwnAgent for tool execution.

Required environment:
  OWNAGENT_BASE_URL
  OWNAGENT_AGENT_API_KEY
  OWNAGENT_TOOL_ADMIN_API_KEY
  OPENAI_API_KEY
  OPENAI_MODEL

Optional:
  OPENAI_BASE_URL (defaults to https://api.openai.com/v1)
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import threading
import uuid
from datetime import datetime, timezone
from typing import Any

import httpx

from ownagent import LocalWorker, OwnAgentClient

TOOL_NAME = "demo.search_workspace"
MAX_STEPS = 6

NOTES = [
    {
        "id": "runtime",
        "text": (
            "OwnAgent Action Runtime accepts an Action, evaluates its registered tool policy, "
            "and persists its lifecycle. Allowed Actions can be claimed by a Worker."
        ),
    },
    {
        "id": "worker",
        "text": (
            "The Local Worker runs developer-owned Python callables registered in its process. "
            "It uses leases and heartbeats and does not download or execute server-supplied code."
        ),
    },
    {
        "id": "approval",
        "text": (
            "Tools marked MEDIUM, HIGH, or CRITICAL require human approval. Approval moves the "
            "Action to READY; rejection moves it to DENIED."
        ),
    },
    {
        "id": "unknown",
        "text": (
            "If a Worker cannot prove whether an external side effect completed, OwnAgent marks "
            "the Action UNKNOWN and does not automatically retry it."
        ),
    },
]


def search_workspace(arguments: dict[str, Any]) -> dict[str, Any]:
    """A read-only local tool. Replace this function with your own data source."""
    query = str(arguments.get("query", "")).strip().lower()
    if not query:
        raise ValueError("query is required")

    terms = {term for term in query.split() if len(term) > 1}
    ranked = []
    for note in NOTES:
        text = note["text"]
        haystack = text.lower()
        score = sum(1 for term in terms if term in haystack)
        if score:
            ranked.append((score, note))

    ranked.sort(key=lambda item: item[0], reverse=True)
    return {
        "query": query,
        "matches": [
            {"id": note["id"], "text": note["text"]}
            for _, note in ranked[:5]
        ],
    }


def _record_span(ownagent: OwnAgentClient, span: dict[str, Any]) -> None:
    """Best-effort telemetry; trace ingestion failures must not hide Agent results."""
    try:
        ownagent.create_trace_span(span)
    except Exception as exc:  # noqa: BLE001
        print(f"Warning: trace span was not persisted: {exc}", file=sys.stderr)


def run_agent(
    llm: httpx.Client,
    ownagent: OwnAgentClient,
    task: str,
    run_id: str,
) -> str:
    # Prompt/response bodies can contain private user data. Capture is opt-in,
    # and every captured value is bounded before it is persisted in Span attributes.
    capture_content = os.environ.get("OWNAGENT_TRACE_CAPTURE_CONTENT", "").lower() in {"1", "true", "yes"}

    def capture(value: Any, limit: int = 3000) -> Any:
        if not capture_content:
            return None
        serialized = json.dumps(value, ensure_ascii=False, default=str)
        if len(serialized) > limit:
            return serialized[:limit] + "… [truncated]"
        return value

    messages: list[dict[str, Any]] = [
        {
            "role": "system",
            "content": (
                "You are a careful research assistant. Use the search_workspace tool to "
                "retrieve relevant local evidence before answering. Clearly distinguish "
                "facts found in the workspace from your own conclusions."
            ),
        },
        {"role": "user", "content": task},
    ]
    tools = [
        {
            "type": "function",
            "function": {
                "name": TOOL_NAME,
                "description": "Search the local OwnAgent example knowledge workspace.",
                "parameters": {
                    "type": "object",
                    "properties": {"query": {"type": "string"}},
                    "required": ["query"],
                    "additionalProperties": False,
                },
            },
        }
    ]

    root_started_at = datetime.now(timezone.utc)
    root_span_id = uuid.uuid4().hex
    for step in range(1, MAX_STEPS + 1):
        llm_started_at = datetime.now(timezone.utc)
        try:
            response = llm.post(
                "chat/completions",
                json={
                    "model": os.environ["OPENAI_MODEL"],
                    "messages": messages,
                    "tools": tools,
                    "tool_choice": "auto",
                    "temperature": 0.2,
                },
            )
            response.raise_for_status()
        except Exception as exc:
            llm_ended_at = datetime.now(timezone.utc)
            _record_span(ownagent, {
                "trace_id": run_id, "span_id": uuid.uuid4().hex,
                "parent_span_id": root_span_id, "run_id": run_id,
                "name": "chat.completions", "span_kind": "CLIENT", "status": "ERROR",
                "started_at": llm_started_at.isoformat(), "ended_at": llm_ended_at.isoformat(),
                "usage_source": "MISSING",
                "attributes": {"provider": "openai-compatible", "model_step": step,
                               "prompt_messages": capture(messages),
                               "error_type": type(exc).__name__,
                               "error_message": str(exc)[:500]},
            })
            _record_span(ownagent, {
                "trace_id": run_id, "span_id": root_span_id, "run_id": run_id,
                "name": "agent.run", "span_kind": "INTERNAL", "status": "ERROR",
                "started_at": root_started_at.isoformat(), "ended_at": llm_ended_at.isoformat(),
                "usage_source": "MISSING", "attributes": {"error_type": type(exc).__name__},
            })
            raise
        llm_ended_at = datetime.now(timezone.utc)
        payload = response.json()
        choices = payload.get("choices") or []
        if not choices:
            failure = RuntimeError("The model returned no choices")
            _record_span(ownagent, {
                "trace_id": run_id, "span_id": uuid.uuid4().hex,
                "parent_span_id": root_span_id, "run_id": run_id,
                "name": "chat.completions", "span_kind": "CLIENT", "status": "ERROR",
                "started_at": llm_started_at.isoformat(), "ended_at": llm_ended_at.isoformat(),
                "usage_source": "MISSING",
                "attributes": {"provider": "openai-compatible", "model_step": step,
                               "prompt_messages": capture(messages),
                               "error_type": type(failure).__name__,
                               "error_message": str(failure)},
            })
            _record_span(ownagent, {
                "trace_id": run_id, "span_id": root_span_id, "run_id": run_id,
                "name": "agent.run", "span_kind": "INTERNAL", "status": "ERROR",
                "started_at": root_started_at.isoformat(), "ended_at": llm_ended_at.isoformat(),
                "usage_source": "MISSING",
                "attributes": {"error_type": type(failure).__name__, "error_message": str(failure)},
            })
            raise failure

        message = choices[0].get("message") or {}
        tool_calls = message.get("tool_calls") or []
        usage = payload.get("usage") or {}
        if usage:
            print(
                f"LLM step {step}: input={usage.get('prompt_tokens', 'n/a')} "
                f"output={usage.get('completion_tokens', 'n/a')} tokens"
            )
        _record_span(ownagent, {
            "trace_id": run_id,
            "span_id": uuid.uuid4().hex,
            "parent_span_id": root_span_id,
            "run_id": run_id,
            "name": "chat.completions",
            "span_kind": "CLIENT",
            "status": "OK",
            "started_at": llm_started_at.isoformat(),
            "ended_at": llm_ended_at.isoformat(),
            "input_tokens": usage.get("prompt_tokens"),
            "output_tokens": usage.get("completion_tokens"),
            "total_tokens": usage.get("total_tokens"),
            "usage_source": "PROVIDER" if usage else "MISSING",
            "attributes": {
                "provider": "openai-compatible",
                "model": payload.get("model"),
                "model_step": step,
                "tool_call_count": len(tool_calls),
                "prompt_messages": capture(messages),
                "tool_schema": capture(tools),
                "model_response": capture(message),
            },
        })
        messages.append(message)

        if not tool_calls:
            _record_span(ownagent, {
                "trace_id": run_id, "span_id": root_span_id, "run_id": run_id,
                "name": "agent.run", "span_kind": "INTERNAL", "status": "OK",
                "started_at": root_started_at.isoformat(),
                "ended_at": llm_ended_at.isoformat(),
                "usage_source": "MISSING",
                "attributes": {"framework": "ownagent-python-demo"},
            })
            return str(message.get("content") or "")

        for call in tool_calls:
            call_id = str(call.get("id") or uuid.uuid4().hex)
            function = call.get("function") or {}
            tool_name = str(function.get("name") or "")
            if tool_name != TOOL_NAME:
                raise RuntimeError(f"Model requested an unregistered tool: {tool_name}")

            try:
                arguments = json.loads(function.get("arguments") or "{}")
            except json.JSONDecodeError as exc:
                raise RuntimeError(f"Model returned invalid tool arguments: {exc}") from exc
            if not isinstance(arguments, dict):
                raise TypeError("Tool arguments must be a JSON object")

            action_started_at = datetime.now(timezone.utc)
            action = None
            try:
                action = ownagent.create_action(
                    tool_name, arguments,
                    idempotency_key=f"{run_id}-{step}-{call_id}"[:128],
                    metadata={"runId": run_id, "toolCallId": call_id, "step": step,
                              "agentFramework": "ownagent-python-demo"},
                )
                if action.decision != "ALLOW" or action.status != "READY":
                    raise RuntimeError(
                        f"Action was not immediately executable: decision={action.decision}, status={action.status}"
                    )
                result = ownagent.wait_for_terminal(action.action_id, timeout=60, poll_interval=0.25)
                if result.status != "SUCCEEDED":
                    raise RuntimeError(
                        f"Action {result.action_id} ended as {result.status}: {result.error_code or ''} {result.error_message or ''}"
                    )
            except Exception as exc:
                _record_span(ownagent, {
                    "trace_id": run_id, "span_id": uuid.uuid4().hex,
                    "parent_span_id": root_span_id, "run_id": run_id,
                    "name": "action.execute", "span_kind": "INTERNAL", "status": "ERROR",
                    "started_at": action_started_at.isoformat(),
                    "ended_at": datetime.now(timezone.utc).isoformat(),
                    "usage_source": "MISSING",
                    "attributes": {"action_id": getattr(action, "action_id", None),
                                   "tool_name": tool_name, "tool_call_id": call_id,
                                   "model_step": step, "tool_arguments": capture(arguments),
                                   "error_type": type(exc).__name__,
                                   "error_message": str(exc)[:500]},
                })
                _record_span(ownagent, {
                    "trace_id": run_id, "span_id": root_span_id, "run_id": run_id,
                    "name": "agent.run", "span_kind": "INTERNAL", "status": "ERROR",
                    "started_at": root_started_at.isoformat(),
                    "ended_at": datetime.now(timezone.utc).isoformat(),
                    "usage_source": "MISSING", "attributes": {"error_type": type(exc).__name__},
                })
                raise

            action_ended_at = datetime.now(timezone.utc)
            _record_span(ownagent, {
                "trace_id": run_id,
                "span_id": uuid.uuid4().hex,
                "parent_span_id": root_span_id,
                "run_id": run_id,
                "name": "action.execute",
                "span_kind": "INTERNAL",
                "status": "OK",
                "started_at": action_started_at.isoformat(),
                "ended_at": action_ended_at.isoformat(),
                "usage_source": "MISSING",
                "attributes": {
                    "action_id": result.action_id,
                    "tool_name": tool_name,
                    "tool_call_id": call_id,
                    "model_step": step,
                    "tool_arguments": capture(arguments),
                    "tool_result": capture(result.output or {}),
                },
            })
            messages.append(
                {
                    "role": "tool",
                    "tool_call_id": call_id,
                    "content": json.dumps(result.output or {}, ensure_ascii=False),
                }
            )

    error = RuntimeError(f"Agent exceeded the maximum of {MAX_STEPS} model steps")
    _record_span(ownagent, {
        "trace_id": run_id, "span_id": root_span_id, "run_id": run_id,
        "name": "agent.run", "span_kind": "INTERNAL", "status": "ERROR",
        "started_at": root_started_at.isoformat(),
        "ended_at": datetime.now(timezone.utc).isoformat(),
        "usage_source": "MISSING", "attributes": {"error_type": type(error).__name__},
    })
    raise error


def main() -> None:
    base_url = os.environ["OWNAGENT_BASE_URL"]
    agent_key = os.environ["OWNAGENT_AGENT_API_KEY"]
    admin_key = os.environ["OWNAGENT_TOOL_ADMIN_API_KEY"]
    openai_key = os.environ["OPENAI_API_KEY"]
    openai_base_url = os.environ.get("OPENAI_BASE_URL", "https://api.openai.com/v1")
    parser = argparse.ArgumentParser(description="Run a real model-driven Agent through OwnAgent.")
    parser.add_argument(
        "task",
        nargs="?",
        default="Explain how OwnAgent's Action Runtime, Local Worker, approval, and UNKNOWN state work together. Search the workspace first and cite the note IDs.",
        help="The task to give the model-driven Agent.",
    )
    args = parser.parse_args()
    task = args.task.strip()
    if not task:
        parser.error("task must not be empty")
    run_id = uuid.uuid4().hex

    stop_worker = threading.Event()
    with OwnAgentClient(
        base_url,
        agent_key,
        tool_admin_api_key=admin_key,
    ) as ownagent:
        ownagent.register_tool(
            TOOL_NAME,
            "Search the local example workspace (read-only)",
            {
                "type": "object",
                "properties": {"query": {"type": "string"}},
                "required": ["query"],
                "additionalProperties": False,
            },
            risk_level="READ_ONLY",
        )

        worker = LocalWorker(ownagent, poll_interval=0.2, heartbeat_interval=10)
        worker.register(TOOL_NAME, search_workspace)
        worker_thread = threading.Thread(
            target=worker.run_forever,
            args=(stop_worker,),
            name="ownagent-demo-worker",
            daemon=True,
        )
        worker_thread.start()

        try:
            with httpx.Client(
                base_url=openai_base_url.rstrip("/") + "/",
                headers={"Authorization": f"Bearer {openai_key}"},
                timeout=60,
            ) as llm:
                print(f"Starting demo Agent run: {run_id}")
                print(f"Task: {task}")
                answer = run_agent(llm, ownagent, task, run_id)
                print("\nFinal answer:\n")
                print(answer)
                print(f"\nRun ID: {run_id}")
        finally:
            stop_worker.set()
            worker_thread.join(timeout=2)


if __name__ == "__main__":
    main()
