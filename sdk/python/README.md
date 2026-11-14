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

The Action is eligible for the Worker only when the server policy returns `ALLOW` and the status is `READY`. The Worker claims only tool names for which the local process has registered a handler. You can run the Agent and Worker as separate processes.

## SDK API

- `register_tool(tool_name, description, input_schema, risk_level="LOW", enabled=True)` — requires `tool_admin_api_key`
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
- Approval-required Actions can be created and inspected, but approval resumption is not implemented yet. A Worker claims only `ALLOW / READY` Actions.
- The SDK is synchronous. Async client, OS-level sandboxing, Worker enrollment/independent credentials, approval resumption, automatic safe retries, MCP integration and framework adapters remain future work.

## Errors

- `OwnAgentAPIError` exposes `status_code`, API `code`, and response `payload`.
- `OwnAgentTimeoutError` means the SDK stopped waiting. It does **not** prove that the server or external side effect stopped.

This package currently lives in the backend monorepo under `sdk/python` so it can evolve against the API contract. It is independently installable from source but is not yet published to PyPI.

See `examples/basic_agent.py` and `examples/local_worker.py`.
