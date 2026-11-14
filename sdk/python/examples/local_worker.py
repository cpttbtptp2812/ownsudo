"""Run a local Worker that executes a developer-owned Python function.

Required environment:
  OWNAGENT_BASE_URL
  OWNAGENT_AGENT_API_KEY
  OWNAGENT_TOOL_ADMIN_API_KEY
"""

from __future__ import annotations

import os
import threading

from ownagent import LocalWorker, OwnAgentClient


def greet(arguments: dict[str, object]) -> dict[str, object]:
    name = str(arguments["name"])
    return {"message": f"Hello, {name}. Executed on this machine."}


def main() -> None:
    with OwnAgentClient(
        os.environ["OWNAGENT_BASE_URL"],
        os.environ["OWNAGENT_AGENT_API_KEY"],
        tool_admin_api_key=os.environ["OWNAGENT_TOOL_ADMIN_API_KEY"],
    ) as client:
        client.register_tool(
            "demo.local_greeting",
            "Create a greeting on the developer's local Worker",
            {
                "type": "object",
                "properties": {"name": {"type": "string"}},
                "required": ["name"],
                "additionalProperties": False,
            },
            risk_level="LOW",
        )

        worker = LocalWorker(client, poll_interval=1.0, heartbeat_interval=15.0)
        worker.register("demo.local_greeting", greet)
        print(f"Worker online. Installed tools: {', '.join(worker.tool_names)}")
        try:
            worker.run_forever()
        except KeyboardInterrupt:
            print("Stopping OwnAgent Local Worker.")


if __name__ == "__main__":
    main()
