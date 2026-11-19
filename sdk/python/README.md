# OwnAgent Python SDK

Early developer preview of the OwnAgent Action Runtime client and Local Python Worker.

The SDK can create and inspect Actions, register tool metadata with a separate developer key, and run explicitly registered Python functions on a developer-controlled machine. The Worker does not download or execute code supplied by the server.

## Install

From this repository:

```bash
cd sdk/python
python -m pip install -e .
```

For development:

```bash
python -m pip install -e ".[dev]"
pytest
ruff check src tests examples
```

## Quick start: run a local Python tool

Start OwnAgent backend and configure these environment variables:

- `OWNAGENT_BASE_URL`: backend URL, for example `http://localhost:8080`
- `OWNAGENT_AGENT_API_KEY`: API key scoped to the Agent whose Actions the Worker will execute
- `OWNAGENT_TOOL_ADMIN_API_KEY`: separate developer key used only to register tool metadata

Create a Worker process:

```python
import os
from ownagent import LocalWorker, OwnAgentClient

def greet(arguments: dict) -> dict:
    return {"message": f"Hello, {arguments['name']}!"}

with OwnAgentClient(
    os.environ["OWNAGENT_BASE_URL"],
    os.environ["OWNAGENT_AGENT_API_KEY"],
    tool_admin_api_key=os.environ["OWNAGENT_TOOL_ADMIN_API_KEY"],
) as client:
    client.register_tool(
        "demo.local_greeting",
        "Create a greeting on my local machine",
        {
            "type": "object",
            "properties": {"name": {"type": "string"}},
            "required": ["name"],
            "additionalProperties": False,
        },
        risk_level="LOW",
    )

    worker = LocalWorker(client)
    worker.register("demo.local_greeting", greet)
    worker.run_forever()
```

Run the example directly with `python examples/local_worker.py`.

Your Agent submits an Action using the same Agent API key:

```python
action = client.create_action(
    "demo.local_greeting",
    {"name": "Ada"},
    idempotency_key="greeting-ada-001",
)
```

The Action is eligible for the Worker when the server policy returns `ALLOW / READY`, or when an Action with `REQUIRE_APPROVAL` has been explicitly approved and returned to `READY`. The Worker claims only tool names for which the local process has registered a handler. You can run the Agent and Worker as separate processes.

## Quick start: run a model-driven Agent

The repository includes a complete example that runs a small model-driven Agent and delegates its tool execution to OwnAgent. The example starts a Local Worker in a background thread, so the Agent loop and Worker run as separate execution roles in one Python process.

Install the SDK and configure the backend credentials above, then add:

- `OPENAI_API_KEY`: API key for an OpenAI-compatible model endpoint
- `OPENAI_MODEL`: model name with tool-calling support
- `OPENAI_BASE_URL`: optional; defaults to `https://api.openai.com/v1`

Run:

```bash
export OPENAI_API_KEY="..."
export OPENAI_MODEL="..."
# Optional for a compatible provider:
export OPENAI_BASE_URL="https://api.openai.com/v1"
python examples/demo_agent.py
```

The example asks the model to research OwnAgent's execution semantics. The model chooses when to call `demo.search_workspace`; the example submits that call as an Action; the Local Worker executes the registered Python function; and the result is returned to the model for its final answer.

The Action lifecycle and tool metadata are persisted by OwnAgent. The model request goes directly from the example to the configured LLM endpoint; the example separately submits a span containing provider-reported token usage to OwnAgent. LLM and Action failures are recorded with `status="ERROR"` when their execution raises. Trace ingestion is best-effort: a telemetry outage is reported to stderr and does not replace the Agent's actual result.

### Inspect the actual prompt and tool execution

By default, the demo records span names, model/provider metadata, timing, status, and provider-reported token usage. It does **not** persist prompt or response bodies.

To deliberately capture the exact message list sent to the model, the model response, tool schema, tool arguments, and tool result in span attributes, opt in before running the demo:

```bash
export OWNAGENT_TRACE_CAPTURE_CONTENT=1
python examples/demo_agent.py
```

Content capture is disabled unless this variable is set to `1`, `true`, or `yes`. Captured values are truncated before ingestion to keep span attributes bounded. Prompts and tool results may contain user data, credentials, or other secrets: enable this only in a controlled environment, review/redact data before sharing a Trace, and turn it off when no longer needed. The Studio labels each captured section separately; if content was not captured, it says so rather than reconstructing it from the span name.

For your own Agent, add the same structured fields to the `attributes` passed to `create_trace_span()` (or `trace_span()`): `prompt_messages`, `model_response`, `tool_schema`, `tool_arguments`, and `tool_result`. OwnAgent does not intercept arbitrary provider requests automatically; your Agent integration must explicitly submit these values.

## SDK API

- `register_tool(tool_name, description, input_schema, risk_level="LOW", enabled=True)` — requires `tool_admin_api_key`
- `list_pending_approvals()`, `approve_action(action_id, reason=None)`, `reject_action(action_id, reason=None)` — require the separate `approver_api_key`
- `create_action(tool_name, arguments, idempotency_key=..., metadata=None)`
- `execute_action(action_id)` — sends exactly one server-side execution request; never automatically retried
- `retry_failed_action(action_id, acknowledge_possible_duplicate_side_effect=True)` — creates a new Action only from a `FAILED` attempt after explicit acknowledgement; `UNKNOWN` is never retryable
- `get_action(action_id)`
- `list_actions(limit=50)`
- `get_events(action_id)`
- `wait_for_terminal(action_id, timeout=60, poll_interval=1)`
- `LocalWorker(client, poll_interval=1, heartbeat_interval=15)`
- `LocalWorker.register(tool_name, handler)`
- `LocalWorker.run_once()` and `LocalWorker.run_forever(stop_event=None)`

## Manual retry semantics

A manual retry creates a new Action with a fresh idempotency key and re-evaluates the current tool policy. The caller must explicitly acknowledge that a handler reported as FAILED may still have caused a partial external side effect. The server rejects retries for `UNKNOWN`; reconcile the external system before submitting a new Action. High-risk tools may require a new approval.

## Safety and execution semantics

- The Worker uses the Agent API key and is scoped to that Agent's Actions. Use a dedicated Agent identity/key for each deployment boundary; key rotation revokes Worker access too.
- Tool metadata is not executable code. The Worker runs only Python callables explicitly registered in the local process.
- Do not register untrusted code or expose the Worker host to untrusted users. This first Worker runs in the host process; it is **not** a sandbox or container isolation boundary.
- Worker claims are leased and use the same server-side fencing token as the Action Runtime. Heartbeats extend an active lease; expired leases cannot be revived.
- A handler exception is recorded as `UNKNOWN`, not `FAILED`, because the handler may have performed a partial external side effect before raising.
- The Worker never blindly retries a claimed Action. If the completion request times out, its outcome is ambiguous; server lease recovery eventually marks an unresolved execution `UNKNOWN`.
- `UNKNOWN` requires reconciliation against the external system before a new Action is submitted.
- The Worker currently processes one Action at a time per process. Run multiple Worker processes for concurrency; the database row lock and lease token prevent duplicate claims of the same Action.
- Approval-required Actions can be listed and decided using the separate approver key. Approval preserves the original `REQUIRE_APPROVAL` policy decision, records the configured approver and reason, and moves the Action to `READY`; rejection moves it to `DENIED`. A Worker can claim an approved Action only after that state transition.
- The SDK is synchronous. Async client, OS-level sandboxing, Worker enrollment/independent credentials, approval resumption, automatic safe retries, MCP integration and framework-specific automatic adapters remain future work.

## Errors

- `OwnAgentAPIError` exposes `status_code`, API `code`, and response `payload`.
- `OwnAgentTimeoutError` means the SDK stopped waiting. It does **not** prove that the server or external side effect stopped.

This package currently lives in the backend monorepo under `sdk/python` so it can evolve against the API contract. It is independently installable from source but is not yet published to PyPI.

See `examples/basic_agent.py`, `examples/local_worker.py`, and `examples/demo_agent.py`.


## Trace ingestion API

Record spans from your own Agent or framework. Supply provider-reported token usage only:

```python
client.create_trace_span({
    "trace_id": trace_id,
    "span_id": span_id,
    "parent_span_id": parent_span_id,
    "run_id": run_id,
    "name": "chat.completions",
    "span_kind": "CLIENT",
    "status": "OK",
    "started_at": started_at.isoformat(),
    "ended_at": ended_at.isoformat(),
    "input_tokens": response.usage.prompt_tokens,
    "output_tokens": response.usage.completion_tokens,
    "usage_source": "PROVIDER",
    "attributes": {"provider": "openai", "model": response.model},
})
spans = client.get_trace_spans(trace_id)
```

Use `usage_source="MISSING"` when the provider does not return usage. Do not put prompts, completions, credentials, or personal data in span attributes. The backend stores span metadata and provider token counts; it does not infer or fabricate usage. Action lifecycle events remain available through the existing Action events endpoint. The example currently emits correlated LLM and Action spans; the context-managed trace helper is available for synchronous Python code; automatic instrumentation for arbitrary frameworks and async adapters remain future work.


## Instrument an external Agent

Use the context manager to report span start and completion. Nested spans automatically inherit the current parent span in the same trace:

```python
with client.trace_span(
    trace_id,
    "agent.run",
    run_id=run_id,
    attributes={"framework": "my-agent"},
) as root:
    with client.trace_span(trace_id, "llm.chat", span_kind="CLIENT") as llm:
        response = model_client.chat.completions.create(...)
        # Only pass usage actually returned by the provider.
        if response.usage:
            llm.set_usage(
                input_tokens=response.usage.prompt_tokens,
                output_tokens=response.usage.completion_tokens,
            )
    with client.trace_span(trace_id, "tool.search", span_kind="INTERNAL"):
        result = search_tool(...)
```

The SDK sends an open `UNSET` span when entering the context and a final `OK` or `ERROR` update when leaving it. Exceptions are re-raised after the error span is attempted. Telemetry delivery is best-effort by default so an observability outage does not replace the Agent's business result; pass `best_effort=False` to make delivery errors visible to the caller. The helper records error type, not exception text, by default. It does not capture prompts, completions, or secrets. Token usage remains missing unless the caller supplies provider-reported values using `set_usage()`.

This is a lightweight synchronous integration helper, not automatic instrumentation for every framework. Async Agent frameworks need an async adapter, which is not included yet.

## Live trace event stream

After loading persisted spans with `get_trace_spans(trace_id)`, subscribe to new span updates:

```python
from itertools import chain

with OwnAgentClient(base_url, agent_api_key) as client:
    events = client.stream_trace_events("run-123")
    first_event = next(events)  # Establish the subscription before reading the snapshot.
    existing = client.get_trace_spans("run-123")
    # Merge snapshot + stream by (traceId, spanId) to avoid a subscription race.
    for event in chain([first_event], events):
        if event["event"] == "trace_span":
            span = event["data"]
            print(span["name"], span["status"])
```

The stream endpoint is `GET /api/v1/traces/{traceId}/events` and uses the same Agent Bearer key as span ingestion. It emits `connected` when the subscription is established and `trace_span` when a span is accepted or finalized. To avoid missing an event between snapshot and subscription, establish the stream first, then load the persisted snapshot and deduplicate by span ID. This is a live notification channel, not durable event storage; reconnect and reload after a disconnect. The current server limits each trace to 10 concurrent subscribers and closes streams after 30 minutes. For live progress, ingest an open span with `status="UNSET"` and no `ended_at`, then submit the same span ID with an end time and final `OK` or `ERROR` status. The backend permits this single finalization update; other conflicting updates are rejected.
