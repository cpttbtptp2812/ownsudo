# External observability integrations

OwnAgent accepts standard **OTLP/HTTP JSON** traces at:

`POST /api/v1/otlp/v1/traces`

Authenticate with the Agent API key:

`Authorization: Bearer <OWNAGENT_AGENT_API_KEY>`

Send the standard OpenTelemetry `ExportTraceServiceRequest` JSON shape. The endpoint maps resource and span attributes, parent IDs, span kind/status, nanosecond timestamps, and GenAI token usage into OwnAgent's trace store. It returns `acceptedSpans` and `rejectedSpans`; partial failures are reported and do not silently become successful spans.

## Export to Phoenix or Langfuse

Set these backend environment variables to forward accepted OTLP payloads to another OTLP/HTTP receiver:

- `OWNAGENT_OTLP_EXPORT_ENDPOINT`: full OTLP traces endpoint (for example a Phoenix or Langfuse OTLP HTTP endpoint).
- `OWNAGENT_OTLP_EXPORT_AUTHORIZATION`: optional complete Authorization header value, such as `Bearer ...` or `Basic ...`.

Export is disabled when the endpoint is empty. OwnAgent remains the source of truth; downstream export failures are logged and do not fail the original ingest. Configure TLS and credentials in the deployment secret store, never in frontend code or committed configuration.

## Compatibility notes

- Phoenix and Langfuse are external observability/evaluation platforms. This integration uses their OTLP compatibility rather than embedding their entire servers.
- Helicone can be used as an OTLP-compatible downstream receiver where supported; it is not inserted as an LLM proxy and does not alter provider routing.
- AgentPrism is a React trace visualization library. Its MIT-licensed UI/data code is not a backend collector; OwnAgent's UI continues to consume persisted trace events and preserves its own Action Runtime and approval lifecycle.
- Token usage is marked `PROVIDER` only when an OTLP span explicitly includes recognized usage attributes. Missing usage remains missing.
- Prompt and response bodies are not synthesized. If an instrumentation library emits them as span attributes, they are preserved subject to OwnAgent's existing 32 KiB attributes limit.
