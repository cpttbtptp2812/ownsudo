# OwnAgent — AI Agent Engineering Platform

**Build, run, observe, and evaluate AI Agents.**

OwnAgent is an open-source engineering platform for the parts of Agent development that are difficult to trust in production: durable execution, tool governance, human approval, observability, and repeatable evaluation.

It is not another chat wrapper and does not aim to replace every Agent framework. OwnAgent provides a focused execution and engineering layer, with REST and a planned Python SDK for integrating externally built Agents.

## What it does

- **Run Actions:** submit tool operations through an Agent-scoped API and persist their lifecycle.
- **Govern tools:** register tool metadata, validate input contracts, and evaluate risk policy before execution.
- **Control execution:** only policy-approved Actions can be claimed by the Runtime.
- **Inspect outcomes:** persist outputs, failures, attempt counts, timestamps, and lifecycle events.
- **Integrate Agents:** use REST today; a typed Python SDK and framework adapters are on the roadmap.

## Architecture

| Component | Responsibility | Technology |
| --- | --- | --- |
| Platform API | Agent identity, Actions, tools, policy and trace endpoints | Java 21, Spring Boot 3 |
| Runtime | Action lifecycle and allowlisted server-side handlers | Spring services |
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
                         RUNNING
                         /     \\
                   SUCCEEDED   FAILED
~~~

The runtime includes two deterministic demonstration handlers:

- demo.echo — returns the supplied message and character count.
- text.transform — uppercases, lowercases, trims, or reverses text.

These handlers demonstrate policy-gated execution and persisted outcomes. Tool Registry metadata does **not** execute arbitrary code. No arbitrary shell, SQL, HTTP, or filesystem execution is enabled.

## Quick API walkthrough

All /api/v1/** requests require an Agent API key:

~~~bash
export OWNAGENT_AGENT_KEY="your-agent-api-key"
export OWNAGENT_BASE_URL="http://localhost:8080"

# 1. Register the demo handler's public input contract.
# Requires both Agent authentication and the separate developer Tool Admin key.
curl -X POST "$OWNAGENT_BASE_URL/api/v1/tools" \\
  -H "Authorization: Bearer $OWNAGENT_AGENT_KEY" \\
  -H "X-OwnAgent-Tool-Admin-Key: $OWNAGENT_TOOL_ADMIN_API_KEY" \\
  -H "Content-Type: application/json" \\
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
curl -X POST "$OWNAGENT_BASE_URL/api/v1/actions" \\
  -H "Authorization: Bearer $OWNAGENT_AGENT_KEY" \\
  -H "Content-Type: application/json" \\
  -d '{
    "toolName": "demo.echo",
    "arguments": { "message": "Hello OwnAgent" },
    "idempotencyKey": "demo-echo-001"
  }'

# 3. Execute the returned Action ID.
curl -X POST "$OWNAGENT_BASE_URL/api/v1/actions/<ACTION_ID>/execute" \\
  -H "Authorization: Bearer $OWNAGENT_AGENT_KEY"

# 4. Inspect its persisted lifecycle events.
curl "$OWNAGENT_BASE_URL/api/v1/actions/<ACTION_ID>/events" \\
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

The exact database and bootstrap settings are documented in the application configuration and deployment files. Never commit real API keys or database credentials.

## Roadmap

| Phase | Focus | Planned outcome |
| --- | --- | --- |
| P0 | Runtime foundation | Durable Action state machine, handler registry, policy-gated execution, event trace |
| P1 | Runtime reliability | Worker leases, cancellation, timeout enforcement, stale-run recovery and safe retry semantics |
| P2 | Observability | Nested spans, LLM/tool call metadata, latency/token metrics, redaction and trace visualization |
| P3 | Evaluation | Versioned datasets, evaluators, experiment runs, baseline comparison and regression gates |
| P4 | Python SDK | Typed client, idempotency helpers, wait/poll and external Agent adapters |
| P5 | Studio | Agent registry, run explorer, trace waterfall and Evaluation workbench |

## Honest implementation boundaries

- Approval-required Actions are persisted as PENDING_APPROVAL; the approval-to-execution transition is not complete yet.
- The current execute endpoint is synchronous. It is not a distributed worker scheduler.
- Timeout enforcement, cancellation, stale-run recovery and safe retries are not complete.
- Current trace events cover Action lifecycle transitions, not nested LLM spans.
- Evaluation and Python SDK are roadmap items, not shipped capabilities.
- Risk levels are developer-configured and do not constitute a complete authorization language or RBAC model.

## Repositories

- Backend: https://github.com/cpttbtptp2812/ownsudo
- Frontend Studio: https://github.com/cpttbtptp2812/builder

Contributions and engineering feedback are welcome.
