from __future__ import annotations

import json

import httpx
import pytest

from ownagent import OwnAgentClient


def test_register_tool_uses_separate_developer_key() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.method == "POST"
        assert request.url.path == "/api/v1/tools"
        assert request.headers["Authorization"] == "Bearer agent-key"
        assert request.headers["X-OwnAgent-Tool-Admin-Key"] == "developer-key"
        body = json.loads(request.content)
        assert body["toolName"] == "demo.local_greeting"
        assert body["riskLevel"] == "LOW"
        return httpx.Response(201, json={"toolName": "demo.local_greeting"})

    with OwnAgentClient(
        "http://localhost:8080",
        "agent-key",
        tool_admin_api_key="developer-key",
        transport=httpx.MockTransport(handler),
    ) as client:
        result = client.register_tool(
            "demo.local_greeting",
            "Greeting",
            {"type": "object", "properties": {}, "additionalProperties": False},
        )

    assert result["toolName"] == "demo.local_greeting"


def test_register_tool_requires_developer_key() -> None:
    with OwnAgentClient("http://localhost:8080", "agent-key") as client:
        with pytest.raises(ValueError, match="tool_admin_api_key"):
            client.register_tool("demo.tool", "Tool", {"type": "object"})
