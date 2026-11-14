"""Minimal example: an external Python Agent using OwnAgent as its action runtime."""
import os

from ownagent import OwnAgentClient

with OwnAgentClient(
    base_url=os.environ.get("OWNAGENT_BASE_URL", "http://localhost:8080"),
    api_key=os.environ["OWNAGENT_AGENT_API_KEY"],
) as ownagent:
    action = ownagent.create_action(
        "demo.echo",
        {"message": "Hello from my Agent"},
        idempotency_key="basic-agent-demo-001",
        metadata={"agentFramework": "custom-python-agent"},
    )
    print("Policy decision:", action.decision, action.status)

    if action.decision == "ALLOW" and action.status == "READY":
        result = ownagent.execute_action(action.action_id)
        print("Execution result:", result.status, result.output)
        print("Trace:", ownagent.get_events(action.action_id))
    elif action.status == "PENDING_APPROVAL":
        print("Waiting for a human approval; this SDK version does not yet resume approvals.")
