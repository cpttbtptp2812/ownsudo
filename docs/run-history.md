# Agent Run history

OwnAgent keeps the OpenTelemetry span records as the detailed execution source of truth and adds a Run projection for product-level history.

## Data model

- `agent_trace_span`: one row per observed span; preserves trace/span/parent identity, timestamps, status, provider-reported token usage and opt-in attributes.
- `agent_run`: one row per Agent run, scoped to the authenticated Agent. It is an aggregate/index over spans, not a second copy of span payloads.
- `agent_run_event`: append-only lifecycle events derived from persisted span start/completion/failure observations.
- `agent_run_evaluation`: append-only AI Judge evaluations linked to an Agent, Trace and (when available) Run ID. Stores the rubric, judge model and structured scores JSON.

A Run ID is taken from `ownagent.run.id` / SDK `AgentRun`. For generic OTLP traces that do not provide one, the trace ID is used as a stable fallback run identity. Session and conversation IDs remain separate metadata and are never treated as Run IDs.

## API

All endpoints require the Agent API key as a Bearer token.

- `GET /api/v1/runs`: latest 200 runs for the authenticated Agent.
- `GET /api/v1/runs/{runId}`: Run summary, ordered spans and lifecycle events.
- `POST /api/v1/traces/{traceId}/judge`: evaluate persisted Trace evidence and save the result.
- `GET /api/v1/traces/{traceId}/evaluations`: list the latest 50 persisted evaluations for that Trace.

## Data integrity and privacy

- Run summaries are recomputed from persisted spans; duplicate span ingestion does not inflate counters.
- Token totals are summed only from stored provider-reported values. Missing usage remains missing.
- Prompt/response content is not copied into Run events. Existing opt-in capture remains on span attributes.
- This migration is additive. It does not rewrite chat history or alter Action Runtime tables.

## Built-in chat runs

The platform's own `POST /api/chat/stream` execution path also creates durable Runs. These are stored separately from authenticated external-Agent Runs because a built-in chat request may not have a registered Agent identity; the platform never invents an Agent ID to satisfy the external Run schema.

- `chat_agent_run`: one row per built-in chat invocation, linked to the real chat session and optionally to the Agent ID supplied by the request.
- `chat_agent_run_event`: ordered append-only request, execution-step, tool-call/result and terminal lifecycle events.
- `chat_agent_run_span`: upserted lifecycle spans for actual model rounds, context/workflow steps and tool calls.
- `chat_agent_run_evaluation`: persisted Judge results for built-in Run evidence.

The chat stream emits a `run` SSE event with its real Run ID and Trace ID. The same identifiers are added to the saved assistant message trace metadata. Run events are written as execution progresses, not reconstructed from the final transcript. The Run Explorer lists built-in Runs by session, polls a selected RUNNING Run, displays spans and ordered events, and can evaluate the persisted evidence with AI Judge.

Provider usage is accumulated per model span only when the upstream response includes usage. If the provider omits usage, the Run remains `MISSING`; no character-based estimate is represented as provider usage. Prompt and model-response content capture follows `OWNAGENT_TRACE_CAPTURE_CONTENT=1`; internal reasoning/analysis fields and assistant reasoning channels are removed before persistence and Judge evaluation. User messages and tool I/O remain part of the existing built-in chat execution record.

Built-in chat endpoints (same access boundary as the existing built-in chat history APIs):

- `GET /api/chat/runs?sessionId={sessionId}`
- `GET /api/chat/runs/{runId}`
- `POST /api/chat/runs/{runId}/judge`

## Migration

Production profile enables Flyway and applies the additive V7–V11 migrations after the existing migrations: V7 creates Trace spans, V8 creates external Agent Run history, V9 creates external Run Judge evaluations, V10 creates Agent-scoped Promptfoo report storage, and V11 creates built-in chat Run / event / span / evaluation storage. The migrations are additive and do not rewrite historical chat messages. Do not apply them manually and do not enable production deployment without a backup and a reviewed migration plan.
