# ch16 tool-to-scope/role table

Every call to `/mcp` itself needs a valid bearer token from the `apa` realm
(`examples/_infra/keycloak/realm-apa.json`, reused unchanged from r02):
`quarkus.http.auth.permission.mcp.policy=authenticated` rejects an
unauthenticated call before the MCP dispatcher runs. Past that gate, each
tool adds its own requirement.

| Tool | Role | Scope | Elicitation confirm | Mutates |
|---|---|---|---|---|
| `orderSummary` | `agent` | none | no | no |
| `cancelOrder` | `agent` | `mcp:tools:write` | yes (accept/decline before the mutation) | yes, only on accept |

- The role comes from the token's `realm_access.roles` claim (standard for
  Keycloak-issued tokens) and is checked with `@RolesAllowed("agent")`.
- The scope comes from the token's `scope` claim. `cancelOrder` checks it by
  hand against `JsonWebToken.getClaim("scope")` rather than
  `@PermissionsAllowed("mcp:tools:write")`: the annotation splits its value
  on the first `:` into a name/action pair
  (`io.quarkus.security.PermissionsAllowed.PERMISSION_TO_ACTION_SEPARATOR`,
  confirmed in the quarkus-security 2.3.2 jar), which collides with a
  colon-separated scope name like `mcp:tools:write`.
- A token requesting only `mcp:tools:read` can call `orderSummary` but gets
  an MCP error from `cancelOrder` (`io.quarkus.security.ForbiddenException`,
  recognized as a security failure by `quarkus-mcp-server-core`'s
  `Failures.isSecurityFailure`), and the order row is left unchanged.
- `cancelOrder`'s elicitation confirm only runs after the role and scope
  checks both clear. Declining returns a typed `REJECTED_BY_USER` result,
  not an error, and writes nothing.
