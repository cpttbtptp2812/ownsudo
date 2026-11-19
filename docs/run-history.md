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

## Migration

Production profile enables Flyway and applies the additive V7–V10 migrations after the existing migrations: V7 creates Trace spans, V8 creates Run history, V9 creates persisted Judge evaluations, and V10 creates Agent-scoped Promptfoo report storage. V10 does not attach Promptfoo reports to individual Runs yet. Do not apply them manually and do not enable production deployment without a backup and a reviewed migration plan.
