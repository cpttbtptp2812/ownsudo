# OwnAgent — AI Agent Engineering Platform

**Build, run, observe, and evaluate AI Agents.**

OwnAgent is an open-source engineering platform for the parts of Agent development that are difficult to trust in production: durable execution, tool governance, human approval, observability, and repeatable evaluation.

It is not another chat wrapper and does not aim to replace every Agent framework. OwnAgent provides a focused execution and engineering layer, with REST and an independently installable Python SDK for integrating externally built Agents.

## What it does

- **Run Actions:** submit tool operations through an Agent-scoped API and persist their lifecycle.
- **Govern tools:** register tool metadata, validate input contracts, and evaluate risk policy before execution.
- **Control execution:** only policy-approved Actions can be claimed by the Runtime.
- **Inspect outcomes:** persist outputs, failures, attempt counts, timestamps, and lifecycle events.
- **Keep long actions alive:** renew active execution leases with heartbeats.
- **Represent uncertainty honestly:** timed-out or interrupted handlers become UNKNOWN rather than falsely reported as failed or retried.
- **Integrate Agents:** use REST or the early-preview Python SDK under `sdk/python`.
- **Run local tools:** use the Local Worker preview to execute explicitly registered Python functions on your own machine.

## Architecture

| Component | Responsibility | Technology |
| --- | --- | --- |
| Platform API | Agent identity, Actions, tools, policy and trace endpoints | Java 21, Spring Boot 3 |
| Runtime | Action lifecycle, leases, heartbeat and allowlisted handlers | Spring services, Java virtual threads |
| Persistence | Agent, Action, Tool and event state | Spring Data JPA, Flyway, MySQL |
| Studio | Agent management, Action Runs and Trace explorer | React 19, TypeScript, Vite (separate builder repository) |
| SDK / Worker | Agent integration and local Python tool execution | Python package (`sdk/python`, early developer preview) |

See the architecture and roadmap in docs/architecture/ownagent-engineering-platform.md.

## Current runtime slice

The current feature branch implements the following Action lifecycle:

~~~text
POST /api/v1/actions
  ├── DENY             -> DENIED
  ├── REQUIRE_APPROVAL -> PENDING_APPROVAL
  │                         ├── POST /approve -> READY (APPROVED)
  │                         └── POST /reject  -> DENIED
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

## Python SDK and Local Worker

An installable Python SDK is available under sdk/python. It supports Action creation, metadata registration, event retrieval, and a LocalWorker that executes explicitly registered Python callables on a developer-controlled machine.

Install from this repository:

~~~bash
cd sdk/python
python -m pip install -e .
~~~

Configure OWNAGENT_BASE_URL, OWNAGENT_AGENT_API_KEY, and OWNAGENT_TOOL_ADMIN_API_KEY, then run:

~~~bash
python examples/local_worker.py
~~~

The Worker claims only ALLOW / READY or approved REQUIRE_APPROVAL / READY Actions for tool names registered in the local process. The Agent and Worker can run as separate processes. See the SDK documentation and examples/local_worker.py for the full workflow.

Important boundaries: this first Worker runs in the host process and is not a sandbox. It uses the Agent API key, processes one Action at a time per process, and does not automatically retry. Handler exceptions and ambiguous completion outcomes become UNKNOWN. Independent Worker enrollment credentials, OS-level sandboxing, and manual reconciliation remain future work. High-risk Actions now require a configured approver before a Worker can claim them.

## Action approval API

The approval endpoints require both the Agent Bearer key and the separate X-OwnAgent-Approver-Key header. The approver identity is taken from server configuration, never from the request body.

~~~http
GET  /api/v1/actions/pending-approval
POST /api/v1/actions/{id}/approve
POST /api/v1/actions/{id}/reject
~~~

Approval preserves the original policy decision REQUIRE_APPROVAL. An approved Action transitions to READY with approvalStatus=APPROVED and can then be executed by the synchronous Runtime or claimed by a matching Local Worker. Rejected Actions transition to DENIED. Both decisions record the configured approver, timestamp, reason, and lifecycle event.

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
| P1 | Developer execution | Python SDK and Local Worker preview; approval-to-execution flow implemented for the single configured approver |
| P2 | Runtime hardening | Worker leases, cancellation, manual reconciliation, safe retry semantics and outbox dispatch |
| P3 | Observability | Nested spans, LLM/tool call metadata, latency/token metrics, redaction and trace visualization |
| P4 | Evaluation | Versioned datasets, evaluators, experiment runs, baseline comparison and regression gates |
| P5 | Studio | Agent registry, run explorer, trace waterfall and Evaluation workbench |

## Honest implementation boundaries

- Approval-required Actions persist PENDING / APPROVED / REJECTED decisions. Approval moves an Action to READY; rejection moves it to DENIED. The original REQUIRE_APPROVAL policy decision is retained for audit.
- The current execute endpoint is synchronous and uses server-installed handlers. It is not a distributed worker scheduler.
- Lease heartbeats and handler timeout handling are implemented for this synchronous runtime. Java interruption is best-effort; a timed-out external side effect may still complete.
- Expired or timed-out executions become UNKNOWN and are not automatically retried. Manual reconciliation UI is not complete.
- Tool Registry metadata does not execute arbitrary code; only explicitly installed server-side handlers run.
- Current trace events cover Action lifecycle transitions, not nested LLM spans.
- The Python SDK is an early developer preview for REST Action operations; it does not provide a Local Worker, custom Python handler execution, async client, or approval resumption.
- Risk levels are developer-configured and do not constitute a complete authorization language or RBAC model.

## Repositories

- Backend: https://github.com/cpttbtptp2812/ownsudo
- Frontend Studio: https://github.com/cpttbtptp2812/builder

Contributions and engineering feedback are welcome.
