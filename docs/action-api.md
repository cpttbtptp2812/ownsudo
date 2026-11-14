# OwnAgent Action API

All `/api/v1` endpoints require an Agent API key:

```http
Authorization: Bearer oa_live_<64 hexadecimal characters>
```

Tool registry writes additionally require `X-OwnAgent-Tool-Admin-Key: $OWNAGENT_TOOL_ADMIN_API_KEY`.

The Agent ID is resolved by the authentication filter; clients cannot choose the owner in request bodies or query parameters.

## Register a tool

`POST /api/v1/tools` creates or updates a tool definition for the authenticated Agent. It requires both the Agent Bearer token and a separate developer credential in `X-OwnAgent-Tool-Admin-Key`. This prevents an Agent runtime key from changing its own risk classification. `GET /api/v1/tools` lists only that Agent's tools and requires the Agent Bearer token.

```json
{
  "toolName": "file.write",
  "description": "Write a text file in the agent workspace",
  "riskLevel": "HIGH",
  "enabled": true,
  "inputSchema": {
    "type": "object",
    "properties": {
      "path": { "type": "string" },
      "content": { "type": "string" }
    },
    "required": ["path", "content"],
    "additionalProperties": false
  }
}
```

Supported schema validation currently covers object properties, required fields, primitive JSON types, and additionalProperties. It is deliberately a documented subset, not a complete JSON Schema implementation.

Risk levels: `READ_ONLY`, `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`.

## Create an Action

`POST /api/v1/actions`

```json
{
  "toolName": "file.write",
  "arguments": { "path": "/tmp/report.txt", "content": "hello" },
  "metadata": { "source": "my-agent" },
  "idempotencyKey": "write-report-2026-11-14-001"
}
```

The tool must be registered under the authenticated Agent. Arguments are validated against its schema before policy evaluation is persisted.

## Initial policy

| Tool risk / state | Decision | Action status |
|---|---|---|
| Not registered | DENY | DENIED |
| Disabled | DENY | DENIED |
| READ_ONLY / LOW | ALLOW | READY |
| MEDIUM / HIGH / CRITICAL | REQUIRE_APPROVAL | PENDING_APPROVAL |

The policy is deterministic and intentionally small. The tool risk level is developer-configured; deployments should govern who can register or change tool definitions. A `READY` Action means policy allowed it, not that execution occurred. `PENDING_APPROVAL` is not executable until the approval lifecycle is implemented.

## Execute an Action

`POST /api/v1/actions/{id}/execute`

The current execution endpoint is synchronous. It claims only an Action with `decision=ALLOW` and `status=READY`, then runs a server-installed handler. The handler must be explicitly registered in the server-side `ActionToolHandlerRegistry`; creating Tool Registry metadata does not install or execute code.

The execution response includes the persisted Action status and output/error fields. Lifecycle events are available at `GET /api/v1/actions/{id}/events`.

## Lease, heartbeat and timeout

Runtime defaults are configurable through environment variables:

| Variable | Default | Meaning |
|---|---:|---|
| `OWNAGENT_ACTION_LEASE_SECONDS` | 300 | Lease lifetime |
| `OWNAGENT_ACTION_HEARTBEAT_INTERVAL_MS` | 10000 | Lease renewal interval |
| `OWNAGENT_ACTION_TIMEOUT_SECONDS` | 60 | Maximum handler duration |
| `OWNAGENT_ACTION_RECOVERY_INTERVAL_MS` | 30000 | Expired lease scan interval |

Heartbeat renewal is accepted only for the matching Agent, Action, RUNNING status, lease token and unexpired lease. An expired lease cannot be revived.

If a handler times out or the request thread is interrupted, the Action becomes `UNKNOWN`, not `FAILED`. The handler thread is interrupted on a best-effort basis, but Java interruption cannot guarantee that an external operation stopped. A side effect may have completed. The lease token is invalidated, a lifecycle event is recorded, and the Action must be reconciled before retrying. Expired leases are also recovered as `UNKNOWN`; the runtime never blindly requeues them.

## Idempotency and isolation

The idempotency key is unique per Agent. Same key + same toolName/arguments returns the existing Action; same key + different operation returns HTTP 409. Object key order is normalized. Every Action lookup and Tool listing is scoped by authenticated Agent ID.

## Not implemented yet

- Approval decision-to-execution transition.
- Python SDK and Local Worker for developer-supplied tools.
- Asynchronous distributed worker scheduling and outbox dispatch.
- Manual reconciliation UI and safe retry workflow.
- Nested LLM/tool spans and evaluation engine.

## Database

Production also requires `OWNAGENT_TOOL_ADMIN_API_KEY` for tool registration. Keep this developer credential separate from Agent runtime API keys. Review production Flyway migrations and back up existing data before deployment.
