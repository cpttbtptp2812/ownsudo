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

The Action lifecycle and tool metadata are persisted by OwnAgent. **The model request itself currently goes directly from the example to the configured LLM endpoint; its token usage is printed from the provider response but is not yet ingested as an OwnAgent LLM trace.** Full model-call tracing is a separate observability milestone.

## SDK API

- `register_tool(tool_name, description, input_schema, risk_level="LOW", enabled=True)` — requires `tool_admin_api_key`
- `list_pending_approvals()`, `approve_action(action_id, reason=None)`, `reject_action(action_id, reason=None)` — require the separate `approver_api_key`
- `create_action(tool_name, arguments, idempotency_key=..., metadata=None)`
- `execute_action(action_id)` — sends exactly one server-side execution request; never automatically retried
- `get_action(action_id)`
- `list_actions(limit=50)`
- `get_events(action_id)`
- `wait_for_terminal(action_id, timeout=60, poll_interval=1)`
- `LocalWorker(client, poll_interval=1, heartbeat_interval=15)`
- `LocalWorker.register(tool_name, handler)`
- `LocalWorker.run_once()` and `LocalWorker.run_forever(stop_event=None)`

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
- The SDK is synchronous. Async client, OS-level sandboxing, Worker enrollment/independent credentials, approval resumption, automatic safe retries, MCP integration and framework adapters remain future work.

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

Use `usage_source="MISSING"` when the provider does not return usage. Do not put prompts, completions, credentials, or personal data in span attributes. The backend stores span metadata and provider token counts; it does not infer or fabricate usage. Action lifecycle events remain available through the existing Action events endpoint and will be correlated into the waterfall in a later UI integration.
