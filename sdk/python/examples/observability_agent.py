"""
Example external Agent with real model and tool spans.

Set OWNAGENT_BASE_URL, OWNAGENT_AGENT_API_KEY, OPENAI_API_KEY, and OPENAI_MODEL.
Prompt/response capture is disabled by default; opt in with OWNAGENT_TRACE_CAPTURE_CONTENT=1.
"""
from __future__ import annotations

import json
import os
import uuid
from typing import Any

import httpx

from ownagent import OwnAgentClient


def get_order(arguments: dict[str, Any]) -> dict[str, Any]:
    """Replace this read-only demo handler with your own application function."""
    order_id = str(arguments.get("order_id", "")).strip()
    if not order_id:
        raise ValueError("order_id is required")
    return {"order_id": order_id, "status": "shipped", "source": "demo-handler"}


def main() -> None:
    ownagent_url = os.environ["OWNAGENT_BASE_URL"]
    ownagent_key = os.environ["OWNAGENT_AGENT_API_KEY"]
    provider_key = os.environ["OPENAI_API_KEY"]
    model = os.environ["OPENAI_MODEL"]
    provider_url = os.environ.get("OPENAI_BASE_URL", "https://api.openai.com/v1")
    capture_content = os.environ.get("OWNAGENT_TRACE_CAPTURE_CONTENT", "").lower() in {
        "1", "true", "yes"
    }

    messages: list[dict[str, Any]] = [
        {
            "role": "system",
            "content": "You are a customer support assistant. Use get_order when an order ID is provided.",
        },
        {"role": "user", "content": "Please check the status of order 1024."},
    ]
    tools = [
        {
            "type": "function",
            "function": {
                "name": "get_order",
                "description": "Look up an order by its ID.",
                "parameters": {
                    "type": "object",
                    "properties": {"order_id": {"type": "string"}},
                    "required": ["order_id"],
                    "additionalProperties": False,
                },
            },
        }
    ]

    with OwnAgentClient(ownagent_url, ownagent_key) as observer:  # noqa: SIM117
        with httpx.Client(
            base_url=provider_url.rstrip("/") + "/",
            headers={"Authorization": f"Bearer {provider_key}"},
            timeout=60.0,
        ) as llm:
            session_id = os.environ.get("OWNAGENT_SESSION_ID", f"demo-session-{uuid.uuid4().hex[:8]}")
            with observer.agent_run(
                "customer-support-agent",
                session_id=session_id,
                attributes={"framework": "custom-python", "provider": "openai-compatible"},
            ) as run:
                for step in range(1, 6):
                    llm_attributes: dict[str, Any] = {
                        "provider": "openai-compatible",
                        "model": model,
                        "model_step": step,
                    }
                    if capture_content:
                        llm_attributes["prompt_messages"] = messages
                        llm_attributes["tool_schema"] = tools

                    with run.span(
                        "llm.chat.completions",
                        span_kind="CLIENT",
                        attributes=llm_attributes,
                    ) as model_span:
                        response = llm.post(
                            "chat/completions",
                            json={
                                "model": model,
                                "messages": messages,
                                "tools": tools,
                                "tool_choice": "auto",
                            },
                        )
                        response.raise_for_status()
                        payload = response.json()
                        usage = payload.get("usage") or {}
                        input_tokens = usage.get("prompt_tokens")
                        output_tokens = usage.get("completion_tokens")
                        total_tokens = usage.get("total_tokens")
                        if any(value is not None for value in (input_tokens, output_tokens, total_tokens)):
                            model_span.set_usage(
                                input_tokens=input_tokens,
                                output_tokens=output_tokens,
                                total_tokens=total_tokens,
                                source="PROVIDER",
                            )
                        choices = payload.get("choices") or []
                        if not choices:
                            raise RuntimeError("Provider response did not contain any choices")
                        assistant_message = choices[0].get("message") or {}
                        if capture_content:
                            model_span.attributes["model_response"] = assistant_message

                    messages.append(assistant_message)
                    tool_calls = assistant_message.get("tool_calls") or []
                    if not tool_calls:
                        print(assistant_message.get("content") or "")
                        return

                    for tool_call in tool_calls:
                        function = tool_call.get("function") or {}
                        tool_name = str(function.get("name") or "")
                        arguments = json.loads(function.get("arguments") or "{}")
                        tool_attributes: dict[str, Any] = {"tool_name": tool_name}
                        if capture_content:
                            tool_attributes["tool_arguments"] = arguments
                        with run.span(f"tool.{tool_name}", attributes=tool_attributes) as tool_span:
                            if tool_name != "get_order":
                                raise ValueError(f"Unsupported tool requested by model: {tool_name}")
                            result = get_order(arguments)
                            if capture_content:
                                tool_span.attributes["tool_result"] = result
                        messages.append({
                            "role": "tool",
                            "tool_call_id": tool_call.get("id"),
                            "content": json.dumps(result, ensure_ascii=False),
                        })
                raise RuntimeError("Agent exceeded the maximum number of model steps")


if __name__ == "__main__":
    main()
