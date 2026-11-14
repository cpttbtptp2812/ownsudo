# OwnAgent Action API (initial integration slice)

The API accepts an action request from an authenticated Agent, resolves an Agent-owned registered tool, validates its declared input schema, evaluates a deterministic policy, persists the decision, and exposes scoped retrieval. Tool execution is not yet implemented.

All endpoints are under `/api/v1` and require:

```http
Authorization: Bearer oa_live_<64 hexadecimal characters>
```

The authenticated Agent ID is resolved by the API-key filter. The request body cannot assign or override the owner.

## Create an Action

```http
POST /api/v1/actions
Content-Type: application/json
Authorization: Bearer $OWNAGENT_AGENT_API_KEY
```

```json
{
  "toolName": "file.write",
  "arguments": {
    "path": "/tmp/report.txt",
    "content": "hello"
  },
  "metadata": {
    "source": "my-agent",
    "conversation_id": "conv-123"
  },
  "idempotencyKey": "write-report-2026-11-14-001"
}
```

A newly persisted request returns HTTP 201. Its initial status and decision are derived from the registered tool risk policy. Repeating the same key for the same Agent and same `toolName` + `arguments` returns the existing Action with HTTP 200. Object key order is normalized before fingerprinting. Reusing the same Agent-scoped key with different operation content returns HTTP 409. Metadata is stored but does not change the operation fingerprint.

The database also enforces a unique constraint on `(agent_id, idempotency_key)`. A concurrent first-write race may return HTTP 409; retry the same key to retrieve the winning Action.

## Retrieve Actions

- `GET /api/v1/actions?limit=50` lists only Actions belonging to the authenticated Agent. The limit is clamped to 1–100.
- `GET /api/v1/actions/{actionId}` returns the Action only when it belongs to the authenticated Agent. A foreign or missing Action returns HTTP 404.

The repository methods include the authenticated `agentId` in every lookup. The owner is never taken from query parameters or the request body.

## Current lifecycle boundary

This is an Action persistence and idempotency API, not an execution endpoint. It does not yet:
- evaluate policies or derive risk scores;
- request or process human approval;
- execute tools or schedule workers;
- append lifecycle events or verify postconditions.

Do not interpret `PENDING_POLICY` as a queued/running action or as evidence that a tool ran. Those lifecycle stages are the next implementation steps.

## Database

Production uses Flyway migrations `V2__create_agent_action.sql` and `V3__create_agent_tool_registry.sql`. The default/local configuration leaves Flyway disabled and uses the project's existing local Hibernate schema behavior. Production Flyway baseline is configured separately in `application-prod.yml`; back up and review an existing database before deployment.
