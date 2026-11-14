# OwnAgent — AI Agent Engineering Platform

**Build, run, observe, and evaluate AI Agents.**

OwnAgent is an open-source engineering platform for the parts of Agent development that are difficult to trust in production: durable execution, tool governance, human approval, observability, and repeatable evaluation.

It is not another chat wrapper and does not aim to replace every Agent framework. OwnAgent provides a focused execution and engineering layer, with REST and a planned Python SDK for integrating externally built Agents.

## What it does

- **Run Actions:** submit tool operations through an Agent-scoped API and persist their lifecycle.
- **Govern tools:** register tool metadata, validate input contracts, and evaluate risk policy before execution.
- **Control execution:** only policy-approved Actions can be claimed by the Runtime.
- **Inspect outcomes:** persist outputs, failures, attempt counts, timestamps, and lifecycle events.
- **Keep long actions alive:** renew active execution leases with heartbeats.
- **Represent uncertainty honestly:** timed-out or interrupted handlers become UNKNOWN rather than falsely reported as failed or retried.
- **Integrate Agents:** use REST today; a typed Python SDK and framework adapters are planned.

## Architecture

| Component | Responsibility | Technology |
| --- | --- | --- |
| Platform API | Agent identity, Actions, tools, policy and trace endpoints | Java 21, Spring Boot 3 |
| Runtime | Action lifecycle, leases, heartbeat and allowlisted handlers | Spring services, Java virtual threads |
| Persistence | Agent, Action, Tool and event state | Spring Data JPA, Flyway, MySQL |
| Studio | Agent management, Action Runs and Trace explorer | React 19, TypeScript, Vite (separate builder repository) |
| SDK | External Agent integration | Python (planned, separate repository) |

See the architecture and roadmap in docs/architecture/ownagent-engineering-platform.md.

## Current runtime slice

The current feature branch implements the following Action lifecycle:

~~~text
POST /api/v1/actions
  ├── DENY             -> DENIED
  ├── REQUIRE_APPROVAL -> PENDING_APPROVAL
  └── ALLOW            -> READY
                           |
                           | POST /api/v1/actions/{id}/execute
                           v
                         RUNNING (lease + heartbeat)
                         /     |      \\
                   SUCCEEDED FAILED  UNKNOWN
~~~

The runtime includes two deterministic demonstration handlers:

- demo.echo — returns the supplied message and character count.
- text.transform — uppercases, lowercases, trims, or reverses text.

These handlers demonstrate policy-gated execution and persisted outcomes. Each RUNNING Action receives a lease; heartbeats extend active leases. Expired leases are recovered as UNKNOWN. Handler timeout or request interruption also becomes UNKNOWN because an interrupted Java thread cannot prove that an external side effect stopped. Tool Registry metadata does **not** execute arbitrary code. No arbitrary shell, SQL, HTTP, or filesystem execution is enabled.

## Quick API walkthrough

All /api/v1/** requests require an Agent API key:

~~~bash
export OWNAGENT_AGENT_KEY="your-agent-api-key"
export OWNAGENT_BASE_URL="http://localhost:8080"

# 1. Register the demo handler's public input contract.
# Requires both Agent authentication and the separate developer Tool Admin key.
curl -X POST "$OWNAGENT_BASE_URL/api/v1/tools" \
  -H "Authorization: Bearer $OWNAGENT_AGENT_KEY" \
  -H "X-OwnAgent-Tool-Admin-Key: $OWNAGENT_TOOL_ADMIN_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "toolName": "demo.echo",
    "description": "Echo a message",
    "riskLevel": "LOW",
    "enabled": true,
    "inputSchema": {
      "type": "object",
      "properties": { "message": { "type": "string" } },
      "required": ["message"],
      "additionalProperties": false
    }
  }'

# 2. Create an Action. The response is READY, not yet executed.
curl -X POST "$OWNAGENT_BASE_URL/api/v1/actions" \
  -H "Authorization: Bearer $OWNAGENT_AGENT_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "toolName": "demo.echo",
    "arguments": { "message": "Hello OwnAgent" },
    "idempotencyKey": "demo-echo-001"
  }'

# 3. Execute the returned Action ID.
curl -X POST "$OWNAGENT_BASE_URL/api/v1/actions/<ACTION_ID>/execute" \
  -H "Authorization: Bearer $OWNAGENT_AGENT_KEY"

# 4. Inspect its persisted lifecycle events.
curl "$OWNAGENT_BASE_URL/api/v1/actions/<ACTION_ID>/events" \
  -H "Authorization: Bearer $OWNAGENT_AGENT_KEY"
~~~

## Local development

Requirements:

- Java 21
- Maven 3.9+
- MySQL 8 (or a configured supported development database)

Configure the required environment variables before starting the service. Production configuration intentionally does not contain default database credentials.

~~~bash
mvn clean verify
mvn spring-boot:run
~~~

Runtime configuration:

| Environment variable | Default | Purpose |
| --- | --- | --- |
| OWNAGENT_ACTION_LEASE_SECONDS | 300 | Lease duration, minimum 30 seconds |
| OWNAGENT_ACTION_HEARTBEAT_INTERVAL_MS | 10000 | Lease renewal cadence |
| OWNAGENT_ACTION_TIMEOUT_SECONDS | 60 | Maximum synchronous handler duration |
| OWNAGENT_ACTION_RECOVERY_INTERVAL_MS | 30000 | Expired lease recovery scan cadence |

The heartbeat interval should be comfortably shorter than the lease duration. Never commit real API keys or database credentials.

## Roadmap

| Phase | Focus | Planned outcome |
| --- | --- | --- |
| P0 | Runtime foundation | Durable Action state machine, handler registry, policy-gated execution, event trace |
| P1 | Developer execution | Python SDK, Local Worker, custom Python tools and approval-to-execution flow |
| P2 | Runtime hardening | Worker leases, cancellation, manual reconciliation, safe retry semantics and outbox dispatch |
| P3 | Observability | Nested spans, LLM/tool call metadata, latency/token metrics, redaction and trace visualization |
| P4 | Evaluation | Versioned datasets, evaluators, experiment runs, baseline comparison and regression gates |
| P5 | Studio | Agent registry, run explorer, trace waterfall and Evaluation workbench |

## Honest implementation boundaries

- Approval-required Actions are persisted as PENDING_APPROVAL; the approval-to-execution transition is not complete yet.
- The current execute endpoint is synchronous and uses server-installed handlers. It is not a distributed worker scheduler.
- Lease heartbeats and handler timeout handling are implemented for this synchronous runtime. Java interruption is best-effort; a timed-out external side effect may still complete.
- Expired or timed-out executions become UNKNOWN and are not automatically retried. Manual reconciliation UI is not complete.
- Tool Registry metadata does not execute arbitrary code; only explicitly installed server-side handlers run.
- Current trace events cover Action lifecycle transitions, not nested LLM spans.
- Evaluation and Python SDK are roadmap items, not shipped capabilities.
- Risk levels are developer-configured and do not constitute a complete authorization language or RBAC model.

## Repositories

- Backend: https://github.com/cpttbtptp2812/ownsudo
- Frontend Studio: https://github.com/cpttbtptp2812/builder

Contributions and engineering feedback are welcome.
