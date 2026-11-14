# OwnAgent Action API

All `/api/v1` endpoints require an Agent API key:

```http
Authorization: Bearer oa_live_<64 hexadecimal characters>\n\nTool registry writes additionally require `X-OwnAgent-Tool-Admin-Key: $OWNAGENT_TOOL_ADMIN_API_KEY`.
```

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

## Idempotency and isolation

The idempotency key is unique per Agent. Same key + same toolName/arguments returns the existing Action; same key + different operation returns HTTP 409. Object key order is normalized. Every Action lookup and Tool listing is scoped by authenticated Agent ID.

## Not implemented yet

This slice does not execute tools, schedule workers, process approvals, append Action events, or verify postconditions. The next product step is the developer-facing Tools and Actions console, followed by approval and runtime lifecycle work.

## Database

Production also requires `OWNAGENT_TOOL_ADMIN_API_KEY` for tool registration. Keep this developer credential separate from Agent runtime API keys.\n\nProduction migrations include `V1__create_agent_api_key.sql`, `V2__create_agent_action.sql`, and `V3__create_agent_tool_registry.sql`. Review the production Flyway configuration and back up existing data before deployment.
