# OwnAgent integrations: reuse before rebuild

This document is the integration decision register. "Supported" means there is a working adapter or API in this repository; a link or future plan is not an implemented integration.

## Already present in the code

| Project / standard | What OwnAgent reuses | Status |
| --- | --- | --- |
| [OpenTelemetry](https://opentelemetry.io/) | OTLP/HTTP JSON trace ingestion, span identity, parent/child relationships, token usage attributes and live span events | Implemented; receiver currently accepts JSON, not OTLP protobuf |
| [AgentPrism](https://github.com/evilmartians/agent-prism) | MIT-licensed OpenTelemetry data adapter, span tree construction, token helpers and required types | Vendored data layer only; its full UI is not bundled |
| [Langfuse](https://github.com/langfuse/langfuse) | Existing OTLP export hook can forward accepted trace payloads to a configured compatible receiver | Configurable export path; not an embedded Langfuse deployment or a tested turnkey connector |
| [Arize Phoenix](https://github.com/Arize-ai/phoenix) | Existing OTLP export hook can forward accepted trace payloads to a configured compatible receiver | Configurable export path; not an embedded Phoenix deployment or a tested turnkey connector |

Backend export configuration:
- `OWNAGENT_OTLP_EXPORT_ENDPOINT`
- `OWNAGENT_OTLP_EXPORT_AUTHORIZATION`

OwnAgent remains the local system of record. Export failures are logged and do not fail trace ingestion.

## Recommended additions (reuse as engines, do not copy whole platforms)

| Project | License / fit | Decision |
| --- | --- | --- |
| [Promptfoo](https://github.com/promptfoo/promptfoo) | Apache-2.0; mature prompt/agent eval CLI, red-team and regression workflows | Integrate as an optional evaluation runner and CI adapter. Do not recreate its CLI or copy its dashboard. |
| [DeepEval](https://github.com/confident-ai/deepeval) | Apache-2.0; pytest-style LLM evaluation and metric library | Optional Python evaluation extra; map results into OwnAgent Evaluation records. |
| [MCP Inspector](https://github.com/modelcontextprotocol/inspector) | MIT; official MCP server/client debugging UI | Link/launch as a developer tool or embed via a supported integration boundary; do not fork its UI into OwnAgent. |
| [OpenInference](https://github.com/Arize-ai/openinference) | Apache-2.0 instrumentation conventions and framework instrumentation | Add as optional SDK instrumentation packages where compatible; keep OTLP as the interchange contract. |
| [AgentOps](https://github.com/AgentOps-AI/agentops) | MIT; agent session analytics and replay | Study event model and compatibility; avoid running a second competing trace store. |
| [Langfuse](https://github.com/langfuse/langfuse) | MIT core with separately licensed enterprise components | Prefer OTLP interoperability / optional sidecar deployment over copying the platform. |
| [Phoenix](https://github.com/Arize-ai/phoenix) | Elastic License 2.0; strong trace/eval workbench | Keep as optional external destination; do not vendor its application code into OwnAgent. |

## Integration principles

1. Do not vendor an entire observability platform when a standard protocol or SDK adapter is enough.
2. Keep OwnAgent's differentiator in the unified Agent Run, Action Runtime, approval lifecycle and the product workflow connecting observe → debug → replay → evaluate.
3. Preserve original trace/span IDs and provider-reported usage. Never fabricate missing tokens, duration, tool results or model messages.
4. Optional integrations must not make the core platform depend on a paid SaaS account or a second always-on service.
5. Check the exact upstream license and third-party notices before vendoring code. A project being on GitHub does not automatically make all its code permissively reusable.
6. Account ownership and API authorization must be enforced before multi-user or public exposure; hiding UI controls is not authorization.

## Product gaps to close

- Persistent Evaluation datasets, experiments, evaluator results and comparison history.
- A supported Promptfoo / DeepEval adapter that writes results against OwnAgent Run IDs.
- OpenInference instrumentation helpers for common Python frameworks.
- A first-class MCP server/tool inspector backed by the existing MCP tool registry.
- Replay based on persisted events, with explicit safeguards against repeating external side effects.
- Account login, tenant ownership, and user-managed Agent API keys. Current Agent API keys are not a user account system.

## Reference catalog

The following curated catalogs are useful for continued upstream discovery:
- https://github.com/talhaanwarch/awesome-llmops-platforms
- https://github.com/StefanYasin/awesome-agent-ops
- https://github.com/danielt69/awesome-agent-observability
