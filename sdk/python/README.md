# OwnAgent Python SDK

Early developer preview of a small, synchronous Python client for the OwnAgent Action Runtime.

> Current scope: create, execute, inspect and poll Actions over REST. This is an SDK package, not the Local Worker. It does not execute arbitrary Python functions remotely.

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
```

## Quick start

```python
import os
from ownagent import OwnAgentClient

with OwnAgentClient(
    base_url="http://localhost:8080",
    api_key=os.environ["OWNAGENT_AGENT_API_KEY"],
) as client:
    action = client.create_action(
        "demo.echo",
        {"message": "Hello from my Agent"},
        idempotency_key="my-agent-run-001",
    )

    if action.decision == "ALLOW" and action.status == "READY":
        result = client.execute_action(action.action_id)
        print(result.status, result.output)
        print(client.get_events(action.action_id))
```

## API

- `create_action(tool_name, arguments, idempotency_key=..., metadata=None)`
- `execute_action(action_id)` — sends exactly one execution request; the SDK never retries it automatically.
- `get_action(action_id)`
- `list_actions(limit=50)`
- `get_events(action_id)`
- `wait_for_terminal(action_id, timeout=60, poll_interval=1)`

Errors:
- `OwnAgentAPIError` exposes `status_code`, API `code`, and response `payload`.
- `OwnAgentTimeoutError` means the SDK stopped waiting. It does **not** prove that the server or external side effect stopped.
- An Action with status `UNKNOWN` is terminal for polling and must be reconciled before a retry.

## Current limitations

- The server currently runs only explicitly installed handlers such as `demo.echo` and `text.transform`.
- Tool registration is not included in this first SDK surface; server-side tool-admin authorization remains separate.
- Approval-required Actions can be created and inspected, but approval resumption is not yet implemented.
- The client is synchronous. Async client, Local Worker protocol, custom Python handlers and framework adapters are future work.
- This package currently lives in the backend monorepo under `sdk/python` so it can evolve against the API contract. It is independently installable and will move to its own repository when repository-creation access is available.

See `examples/basic_agent.py` for a complete minimal flow.
