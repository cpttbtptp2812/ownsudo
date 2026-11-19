# Agent Run history

OwnAgent keeps the OpenTelemetry span records as the detailed execution source of truth and adds a Run projection for product-level history.

## Data model

- `agent_trace_span`: one row per observed span; preserves trace/span/parent identity, timestamps, status, provider-reported token usage and opt-in attributes.
- `agent_run`: one row per Agent run, scoped to the authenticated Agent. It is an aggregate/index over spans, not a second copy of span payloads.
- `agent_run_event`: append-only lifecycle events derived from persisted span start/completion/failure observations.

A Run ID is taken from `ownagent.run.id` / SDK `AgentRun`. For generic OTLP traces that do not provide one, the trace ID is used as a stable fallback run identity. Session and conversation IDs remain separate metadata and are never treated as Run IDs.

## API

All endpoints require the Agent API key as a Bearer token.

- `GET /api/v1/runs`: latest 200 runs for the authenticated Agent.
- `GET /api/v1/runs/{runId}`: Run summary, ordered spans and lifecycle events.

## Data integrity and privacy

- Run summaries are recomputed from persisted spans; duplicate span ingestion does not inflate counters.
- Token totals are summed only from stored provider-reported values. Missing usage remains missing.
- Prompt/response content is not copied into Run events. Existing opt-in capture remains on span attributes.
- This migration is additive. It does not rewrite chat history or alter Action Runtime tables.

## Migration

Production profile enables Flyway and applies `V8__create_agent_run_history.sql` after the existing migrations. Do not apply it manually and do not enable production deployment without a backup and a reviewed migration plan.
