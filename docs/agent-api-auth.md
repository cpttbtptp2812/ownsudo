# Agent API key authentication

The first integration slice protects the `/api/v1/**` namespace with per-Agent bearer credentials. Existing legacy `/api/**` endpoints are not covered by this filter and must not be treated as tenant-isolated.

## Bootstrap one Agent key

1. Create an Agent in the existing OwnAgent console and note its Agent ID.
2. Generate a high-entropy token. The required format is `oa_live_` followed by exactly 64 hexadecimal characters:

   ```bash
   printf 'oa_live_%s\n' "$(openssl rand -hex 32)"
   ```

3. Configure these environment variables for the production service:

   ```bash
   OWNAGENT_BOOTSTRAP_AGENT_ID=agent-your-id
   OWNAGENT_BOOTSTRAP_AGENT_API_KEY=oa_live_<64-hex-characters>
   ```

4. Restart the service. The bootstrap runner verifies that the Agent exists and stores only the SHA-256 hash. It never stores the raw API key.
5. Keep the raw key in a secret manager. Do not commit it, print it in application logs, or put it in browser-side code.

The bootstrap key is associated with the fixed key name `bootstrap` for that Agent. Replacing the configured token and restarting rotates that credential. This initial bootstrap supports one Agent/key pair; a multi-key management API and revocation UI are not part of this change.

## Verify the integration

```bash
curl -i https://your-ownagent-host/api/v1/identity \
  -H "Authorization: Bearer $OWNAGENT_BOOTSTRAP_AGENT_API_KEY"
```

A valid key returns the authenticated `agent_id`. Missing, malformed, unknown, inactive, or revoked keys return HTTP 401.

## Schema migration

Production enables Flyway with baseline version 0 so an existing database without Flyway history can apply the new `V1__create_agent_api_key.sql` migration. Review the target database and take a backup before deploying schema changes.

## Security boundary

This is the authentication foundation for the new versioned integration API. It does not retroactively secure legacy `/api/**` routes, does not yet implement end-user roles, and does not itself prove that every legacy resource is isolated by Agent. Do not expose those legacy routes as a multi-tenant API until their authorization and ownership checks are implemented.
