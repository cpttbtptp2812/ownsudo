# OwnAgent — AI Agent Engineering Platform

**Build, run, observe, and evaluate AI Agents.**

OwnAgent is an open-source engineering platform for the parts of Agent development that are difficult to trust in production: durable execution, tool governance, human approval, observability, and repeatable evaluation.

It is not another chat wrapper and does not aim to replace every Agent framework. OwnAgent provides a focused execution and engineering layer, with REST and an independently installable Python SDK for integrating externally built Agents.

## Persisted Agent Run history

The observability API now materializes a queryable Run index from accepted trace spans:

- `GET /api/v1/runs` lists recent runs for the authenticated Agent.
- `GET /api/v1/runs/{runId}` returns the Run summary, ordered spans, and persisted span lifecycle events.
- `agent_run` stores the Run-level projection; `agent_run_event` stores append-only span lifecycle observations; `agent_trace_span` remains the detailed span source of truth.
- Provider token totals are aggregated only from spans marked `PROVIDER`; estimated or missing usage is never represented as provider usage.

Built-in chat executions now create their own persisted Run / Trace records as the stream progresses. `GET /api/chat/runs?sessionId=...` lists them, `GET /api/chat/runs/{runId}` returns ordered events and spans, and `POST /api/chat/runs/{runId}/judge` evaluates the stored evidence. Built-in runs remain separate from external Agent Runs when no real Agent identity is supplied; the chat SSE stream emits the Run and Trace IDs, and the Run Explorer refreshes active runs.

See [docs/run-history.md](docs/run-history.md). The additive V7–V11 Flyway migrations create external Trace / Run history, persisted external Judge evaluations, Promptfoo report storage, and built-in chat Run / event / span / evaluation tables. They are applied by the production profile. No production database was modified as part of this change. See [docs/integrations.md](docs/integrations.md) for the upstream reuse register, OTLP/HTTP JSON and Protobuf ingestion, Promptfoo report import/history, current export hooks, license boundaries, and recommended DeepEval / MCP Inspector integrations.

## OpenTelemetry and Promptfoo report APIs

The authenticated OTLP receiver accepts both standard OTLP/HTTP JSON and Protobuf at `POST /api/v1/otlp/v1/traces`. Use the official OTLP HTTP JSON exporter or Protobuf exporter; both feed the same span normalization, Run projection, persistence and live-event path.

Promptfoo remains an external CLI runner. OwnAgent does not execute arbitrary Promptfoo projects on the server. Instead, an Agent API key can import an exported JSON / JSONL report and retrieve its saved history:

~~~http
POST /api/v1/evaluations/promptfoo/import
GET  /api/v1/evaluations/promptfoo
GET  /api/v1/evaluations/promptfoo/{reportId}
~~~

Imports are scoped to the authenticated Agent, limited to 20 MB, and have internal reasoning/analysis fields and assistant analysis-channel messages removed before persistence. Include an optional `runId` in the import JSON body to link the report to an existing Run; the backend verifies that the Run belongs to the authenticated Agent. Reports can later be listed and reopened without repeating the import. Apply Flyway V10 and V11 when deploying this branch.

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

The fastest way to verify the core OwnAgent business flow is the model-driven example below. It runs a real OpenAI-compatible model call, turns the model's tool call into an OwnAgent Action, lets a Python Local Worker execute an explicitly registered function, returns the real result to the model, and records the model/Action spans for the Studio.

### Run the complete Agent → Action → Worker → answer flow

**1. Start the backend.** The default local profile uses an embedded H2 database, so Java 21 and Maven are enough for a clean local start:

~~~bash
# Set these before starting Spring Boot.
export OWNAGENT_BOOTSTRAP_AGENT_ID=ownagent-local-demo
export OWNAGENT_BOOTSTRAP_AGENT_API_KEY="oa_live_$(openssl rand -hex 32)"
export OWNAGENT_TOOL_ADMIN_API_KEY="$(openssl rand -hex 32)"

mvn spring-boot:run
~~~

On the local profile only, OwnAgent creates the configured bootstrap Agent when it does not exist yet. The raw Agent key is stored only as a SHA-256 hash. The production profile does not create missing Agents.

**2. In another terminal, install the SDK and run the model-driven Agent.** Use the same Agent and Tool Admin keys from the backend terminal:

~~~bash
cd sdk/python
python -m pip install -e .

export OWNAGENT_BASE_URL=http://localhost:8080
export OWNAGENT_AGENT_API_KEY="paste-the-OWNAGENT_BOOTSTRAP_AGENT_API_KEY-value"
export OWNAGENT_TOOL_ADMIN_API_KEY="paste-the-OWNAGENT_TOOL_ADMIN_API_KEY-value"
export OPENAI_API_KEY="your-model-provider-key"
export OPENAI_MODEL="gpt-4.1-mini"
# Optional for an OpenAI-compatible provider:
# export OPENAI_BASE_URL="https://your-provider.example/v1"

python examples/demo_agent.py
~~~

The example registers a read-only workspace search tool, starts a Local Worker, asks the model to use the tool, submits the model's actual arguments as an Action, waits for the Worker result, and sends that result back to the model for its final answer. The Worker runs a Python function registered in the local process; it does not download or execute server-supplied code.

**3. Observe the same execution.** Open Studio → 运行观测 → 外部 Agent Runs, enter `OWNAGENT_AGENT_API_KEY`, and load Runs. The demo prints its Run ID. The displayed model and Action spans use the same Run ID and contain provider-reported token usage, actual timing, tool name, Action outcome, and errors. Prompt/response bodies remain off by default.

The one-flow example is also covered by an automated SDK integration test that runs the real `LocalWorker` against a stateful HTTP Action Runtime test double and verifies that the Worker result is returned to the model.

### Run the Worker separately

`examples/local_worker.py` is available when you want the Agent and Worker in separate processes. It registers a local greeting handler and polls the Runtime for matching Actions. The Agent and Worker must use the same Agent API key; the developer Tool Admin key is needed only by the process that registers tool metadata.

Important boundaries: the Worker runs on the developer-controlled host and is not a sandbox. It processes one Action at a time per process and never blindly retries. Handler exceptions or ambiguous completion outcomes become UNKNOWN; reconcile the external system before submitting a new Action. High-risk Actions require a separately configured approver.

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
export OWNAGENT_AGENT_API_KEY="your-agent-api-key"
export OWNAGENT_BASE_URL="http://localhost:8080"

# 1. Register the demo handler's public input contract.
# Requires both Agent authentication and the separate developer Tool Admin key.
curl -X POST "$OWNAGENT_BASE_URL/api/v1/tools" \
  -H "Authorization: Bearer $OWNAGENT_AGENT_API_KEY" \
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
  -H "Authorization: Bearer $OWNAGENT_AGENT_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "toolName": "demo.echo",
    "arguments": { "message": "Hello OwnAgent" },
    "idempotencyKey": "demo-echo-001"
  }'

# 3. Execute the returned Action ID.
curl -X POST "$OWNAGENT_BASE_URL/api/v1/actions/<ACTION_ID>/execute" \
  -H "Authorization: Bearer $OWNAGENT_AGENT_API_KEY"

# 4. Inspect its persisted lifecycle events.
curl "$OWNAGENT_BASE_URL/api/v1/actions/<ACTION_ID>/events" \
  -H "Authorization: Bearer $OWNAGENT_AGENT_API_KEY"
~~~

## Local development

Requirements:

- Java 21
- Maven 3.9+
- No database installation is required for local development; the local profile uses an embedded H2 file database
- MySQL 8 is used by the production profile

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
| P3 | Observability | Span ingestion, Python demo instrumentation and authenticated live span SSE notifications implemented; next: durable event replay, redaction, automatic framework instrumentation and richer trace visualization |
| P4 | Evaluation | Versioned datasets, evaluators, experiment runs, baseline comparison and regression gates |
| P5 | Studio | Agent registry, run explorer, trace waterfall and Evaluation workbench |

## Live chat prompt capture

The built-in chat endpoint can emit inspectable model-request and tool-execution details to the conversation timeline. To capture prompt/response bodies, explicitly set this environment variable on the backend process before starting the service:

```bash
OWNAGENT_TRACE_CAPTURE_CONTENT=1
```

When enabled, the chat trace records the actual assembled message list sent to each model round (system prompt after memory/RAG/context injection, conversation history, tool messages), the model's returned assistant message/tool calls, tool arguments/results, and the final streamed answer. Captured payloads are bounded before being sent to the browser. When disabled (the default), model-call lifecycle and metadata are still emitted, but message bodies are omitted.

**Privacy:** prompts, history, retrieved context and tool results may contain personal or confidential data. Enable capture only in a controlled environment, do not share traces without review/redaction, and turn it off after debugging. This feature does not expose hidden chain-of-thought; it records provider request/response payloads and explicit tool I/O only.

## Honest implementation boundaries

- Approval-required Actions persist PENDING / APPROVED / REJECTED decisions. Approval moves an Action to READY; rejection moves it to DENIED. The original REQUIRE_APPROVAL policy decision is retained for audit.
- The current execute endpoint is synchronous and uses server-installed handlers. It is not a distributed worker scheduler.
- Lease heartbeats and handler timeout handling are implemented for this synchronous runtime. Java interruption is best-effort; a timed-out external side effect may still complete.
- Expired or timed-out executions become UNKNOWN and are not automatically retried. Manual reconciliation UI is not complete.
- Tool Registry metadata does not execute arbitrary code; only explicitly installed server-side handlers run.
- The Trace API persists submitted spans, including parent-child relationships and provider-reported token usage. The authenticated `GET /api/v1/traces/{traceId}/events` endpoint streams newly accepted and finalized spans to connected clients. An open `UNSET` span can be finalized once with an end time and `OK` or `ERROR` status, allowing clients to observe start and completion separately. Persisted spans remain the source of truth; subscribe before loading the snapshot, then deduplicate by span ID. The runnable Python Agent demo emits LLM-call, Action-execution, and root Agent spans, including ERROR spans for covered failures. This is example-level instrumentation, not automatic instrumentation for arbitrary Agent frameworks.
- The Python SDK is an early developer preview with synchronous REST Action operations, tool metadata registration, approval methods, trace ingestion/retrieval, a live trace event iterator, and a Local Worker for explicitly registered Python callables. Async client support, independent Worker enrollment credentials, OS-level sandboxing, and automatic instrumentation for third-party Agent frameworks remain future work.
- Risk levels are developer-configured and do not constitute a complete authorization language or RBAC model.

## Repositories

- Backend: https://github.com/cpttbtptp2812/ownsudo
- Frontend Studio: https://github.com/cpttbtptp2812/builder

Contributions and engineering feedback are welcome.
