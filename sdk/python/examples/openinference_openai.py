"""Auto-instrument an OpenAI SDK Agent with OpenInference and export OTLP/JSON to OwnAgent.

Install:
  python -m pip install -e ".[otel-json,openinference-openai]" openai>=2.8

Required environment:
  OWNAGENT_OTLP_ENDPOINT=http://localhost:8080/api/v1/otlp/v1/traces
  OWNAGENT_AGENT_API_KEY=oa_live_...
  OPENAI_API_KEY=...
Optional:
  OPENAI_MODEL=gpt-4.1-mini
  OWNAGENT_SESSION_ID=my-conversation
  OWNAGENT_TRACE_CAPTURE_CONTENT=1  # opt in to prompt/response capture
"""
from __future__ import annotations

import os
import uuid

from openai import OpenAI
from openinference.instrumentation import TraceConfig
from openinference.instrumentation.openai import OpenAIInstrumentor
from opentelemetry import trace
from opentelemetry.exporter.otlp.json.http.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor


def main() -> None:
    endpoint = os.environ["OWNAGENT_OTLP_ENDPOINT"]
    agent_key = os.environ["OWNAGENT_AGENT_API_KEY"]
    capture_content = os.environ.get("OWNAGENT_TRACE_CAPTURE_CONTENT", "").lower() in {
        "1",
        "true",
        "yes",
    }
    run_id = uuid.uuid4().hex
    session_id = os.environ.get("OWNAGENT_SESSION_ID", f"session-{uuid.uuid4().hex[:8]}")

    # Resource attributes are attached to every exported span. This lets OwnAgent
    # group the instrumented model spans into one Run, even when the framework
    # creates those spans automatically.
    resource = Resource.create(
        {
            "service.name": "ownagent-openinference-example",
            "ownagent.run.id": run_id,
            "session.id": session_id,
        }
    )
    provider = TracerProvider(resource=resource)
    exporter = OTLPSpanExporter(
        endpoint=endpoint,
        headers={"Authorization": f"Bearer {agent_key}"},
    )
    provider.add_span_processor(BatchSpanProcessor(exporter))
    trace.set_tracer_provider(provider)

    # Do not persist user prompts or model output unless explicitly opted in.
    privacy_config = TraceConfig(
        hide_inputs=not capture_content,
        hide_outputs=not capture_content,
    )
    instrumentor = OpenAIInstrumentor()
    instrumentor.instrument(tracer_provider=provider, config=privacy_config)

    client = OpenAI()
    tracer = trace.get_tracer(__name__)
    try:
        with tracer.start_as_current_span("agent.run") as run_span:
            run_span.set_attribute("ownagent.run.id", run_id)
            run_span.set_attribute("session.id", session_id)
            response = client.chat.completions.create(
                model=os.environ.get("OPENAI_MODEL", "gpt-4.1-mini"),
                messages=[
                    {
                        "role": "system",
                        "content": "You are a concise assistant. Explain what an execution trace is.",
                    },
                    {"role": "user", "content": "Give me a short explanation and one example."},
                ],
            )
            print(response.choices[0].message.content or "")
            if response.usage:
                print(
                    "Provider usage:",
                    response.usage.prompt_tokens,
                    "input /",
                    response.usage.completion_tokens,
                    "output tokens",
                )
    finally:
        instrumentor.uninstrument()
        provider.shutdown()


if __name__ == "__main__":
    main()
