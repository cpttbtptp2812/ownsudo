"""Auto-instrument the OpenAI Agents SDK and export OTLP/JSON traces to OwnAgent.

Install from the repository's sdk/python directory:
  python -m pip install -e ".[otel-json,openinference-openai-agents]" "openai-agents>=0.11.0"

Required environment:
  OWNAGENT_OTLP_ENDPOINT=http://localhost:8080/api/v1/otlp/v1/traces
  OWNAGENT_AGENT_API_KEY=oa_live_...
  OPENAI_API_KEY=...
Optional:
  OWNAGENT_TRACE_CAPTURE_CONTENT=1
"""
from __future__ import annotations

import os
import uuid

from agents import Agent, Runner
from openinference.instrumentation import TraceConfig
from openinference.instrumentation.openai_agents import OpenAIAgentsInstrumentor
from opentelemetry import trace
from opentelemetry.exporter.otlp.json.http.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor


def main() -> None:
    run_id = uuid.uuid4().hex
    session_id = os.getenv("OWNAGENT_SESSION_ID", f"session-{uuid.uuid4().hex[:8]}")
    provider = TracerProvider(
        resource=Resource.create(
            {
                "service.name": "ownagent-openai-agents-example",
                "ownagent.run.id": run_id,
                "session.id": session_id,
            }
        )
    )
    provider.add_span_processor(
        BatchSpanProcessor(
            OTLPSpanExporter(
                endpoint=os.environ["OWNAGENT_OTLP_ENDPOINT"],
                headers={"Authorization": f"Bearer {os.environ['OWNAGENT_AGENT_API_KEY']}"},
            )
        )
    )

    capture_content = os.getenv("OWNAGENT_TRACE_CAPTURE_CONTENT", "").lower() in {
        "1",
        "true",
        "yes",
    }
    instrumentor = OpenAIAgentsInstrumentor()
    instrumentor.instrument(
        tracer_provider=provider,
        config=TraceConfig(
            hide_inputs=not capture_content,
            hide_outputs=not capture_content,
        ),
    )
    trace.set_tracer_provider(provider)

    agent = Agent(
        name="ResearchAssistant",
        instructions="Answer clearly and briefly. Do not invent sources.",
    )
    try:
        with trace.get_tracer(__name__).start_as_current_span("agent.run") as span:
            span.set_attribute("ownagent.run.id", run_id)
            span.set_attribute("session.id", session_id)
            result = Runner.run_sync(
                agent,
                "Explain why parent-child spans are useful in Agent debugging.",
            )
            print(result.final_output)
    finally:
        instrumentor.uninstrument()
        provider.shutdown()


if __name__ == "__main__":
    main()
