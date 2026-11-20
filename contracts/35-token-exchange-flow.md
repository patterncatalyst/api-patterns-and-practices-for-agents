# ch35 RFC 8693 token exchange and acr step-up

Confirmed live against the pinned `quay.io/keycloak/keycloak:26.8.2` image and
the realm additions in `examples/_infra/keycloak/realm-apa.json`: Standard
Token Exchange v2 (`token-exchange-standard:v2`) is on by default in 26.8.2,
so no realm-level feature flag is set.

## 1. The low-assurance token

`apa-agent-bff` (a confidential client with `serviceAccountsEnabled: true`)
fetches its own token by `client_credentials`, requesting only the read
scope:

```
POST /realms/apa/protocol/openid-connect/token
grant_type=client_credentials
client_id=apa-agent-bff
client_secret=<apa-agent-bff's secret>
scope=mcp:tools:read
```

The returned access token carries `scope: "mcp:tools:read"`, the `agent`
realm role, and no `acr` claim at all: this is the low-assurance token the
read tool accepts and the destructive tool must refuse.

## 2. The exchange

The same client trades that token for a step-up, write-scoped one:

```
POST /realms/apa/protocol/openid-connect/token
grant_type=urn:ietf:params:oauth:grant-type:token-exchange
client_id=apa-agent-bff
client_secret=<apa-agent-bff's secret>
subject_token=<the token from step 1>
subject_token_type=urn:ietf:params:oauth:token-type:access_token
requested_token_type=urn:ietf:params:oauth:token-type:access_token
audience=apa-mcp
scope=mcp:tools:write step-up-acr
```

RFC 8693 requires the requester client to be confidential, in the same
realm as the subject token, and present in that token's `aud` claim. The
subject token above carries no `aud` claim at all; the requirement is
waived because the token was issued to `apa-agent-bff` itself (the same
client now doing the exchange), confirmed live: the identical request from
`apa-mcp`, a client with no `standard.token.exchange.enabled` attribute,
is rejected outright with `"Standard token exchange is not enabled for the
requested client"`, never reaching an aud check at all.

The exchanged token's claims come from the **requester client's** own
client scopes, not the audience client's: `apa-agent-bff`'s
`step-up-acr` optional client scope carries two protocol mappers that only
apply when that scope is requested in the exchange call itself:

| Mapper | Effect |
|---|---|
| `oidc-hardcoded-claim-mapper` | Stamps `acr: "urn:patterncatalyst:acr:step-up"` |
| `oidc-audience-mapper` | Adds `apa-mcp` to the token's `aud` claim |

The `audience=apa-mcp` request parameter itself only resolves because an
`oidc-audience-mapper` naming `apa-mcp` is reachable through a scope the
requester can request; omitting that mapper reproduces, live, the error
`"Requested audience not available: apa-mcp"`.

## 3. The resulting token

```json
{
  "iss": "http://localhost:8180/realms/apa",
  "aud": "apa-mcp",
  "azp": "apa-agent-bff",
  "acr": "urn:patterncatalyst:acr:step-up",
  "realm_access": { "roles": ["agent"] },
  "scope": "mcp:tools:write"
}
```

This token clears every gate `cancelOrder` checks: the `agent` role, the
`mcp:tools:write` scope (checked by hand against the `scope` claim, the
same `JsonWebToken` pattern the plain scope-gated MCP pattern uses), and
the `acr` step-up floor (checked by hand against the `acr` claim). A token
exchanged with `scope=step-up-acr` alone (omitting `mcp:tools:write`)
comes back with the high `acr` but an empty `scope` claim, confirmed
live: the destructive tool still rejects it, proving the scope and acr
checks are independent gates, not one check standing in for the other.

## Reference

RFC 8693 (OAuth 2.0 Token Exchange) and the `acr` (Authentication Context
Class Reference) claim from OpenID Connect Core 1.0 are cited in full in
the bibliography.
