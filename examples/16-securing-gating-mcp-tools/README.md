# 16 Securing and gating MCP tools

Puts three guards in front of a two-tool MCP surface, stacked in the order a caller meets them:

1. **OIDC bearer on `/mcp`.** The whole endpoint, not just individual tools, requires a verified
   bearer token from the r02 `apa` Keycloak realm (`examples/_infra/keycloak/realm-apa.json`,
   reused unchanged): `quarkus.http.auth.permission.mcp.paths=/mcp` with
   `policy=authenticated` rejects an unauthenticated call before the MCP dispatcher ever runs.
2. **Role on the read.** `OrderMcpTools.orderSummary` carries `@RolesAllowed("agent")`. The
   realm's `realm_access.roles` claim (standard for Keycloak-issued tokens) and its `roles`
   client scope both map onto `SecurityIdentity` roles with no extra `quarkus.oidc.roles.*`
   configuration.
3. **Scope plus confirm on the write.** `cancelOrder` first checks the token's `scope` claim for
   `mcp:tools:write` by hand, then (only once that clears) asks an `Elicitation` to confirm the
   cancellation and refund before writing anything.

## Why the scope check is manual, not `@PermissionsAllowed`

quarkus-oidc does map a bearer token's `scope` claim onto `SecurityIdentity` permissions
automatically, as `io.quarkus.security.StringPermission`. The obvious move is
`@PermissionsAllowed("mcp:tools:write")`. But `@PermissionsAllowed`'s own annotation value splits
on the *first* `:` into a permission name and an action
(confirmed in the quarkus-security 2.3.2 jar:
`PermissionsAllowed.PERMISSION_TO_ACTION_SEPARATOR = ":"`), which collides with a colon-separated
scope name: `@PermissionsAllowed("mcp:tools:write")` checks for permission name `mcp` with action
`tools:write`, never the whole-string `StringPermission("mcp:tools:write")` OIDC actually adds.
`cancelOrder` instead injects `org.eclipse.microprofile.jwt.JsonWebToken` and checks the raw
`scope` claim directly, sidestepping the collision. See `contracts/16-mcp-scopes.md` for the full
tool-to-scope/role table.

## The Elicitation confirm

`cancelOrder` only reaches `Elicitation.requestBuilder()...sendAndAwait()` after both the role and
the scope checks clear. The confirm carries a boolean schema property (a plain yes/no question has
no content to elicit, but `ElicitationRequest.Builder.build()` requires at least one schema
property; confirmed from the quarkus-mcp-server-core 2.0.4 jar). Branching on
`ElicitationResponse.actionAccepted()`:

- **Accept** cancels the order and reports the refunded amount.
- **Decline or cancel** writes nothing and returns a typed `REJECTED_BY_USER` result. This is not
  an error: the caller asked permission, the person at the keyboard said no, and nothing happened.

`@WrapBusinessError` turns `OrderNotFoundException` and `OrderNotCancellableException` into
instructive MCP tool errors (ch15's pattern, confirmed again here: a thrown business exception
needs `@WrapBusinessError` to become an `isError` tool result instead of an opaque failure). The
scope guard throws `io.quarkus.security.ForbiddenException` instead, which
`quarkus-mcp-server-core`'s `Failures.isSecurityFailure` recognizes on its own (alongside
`UnauthorizedException` and `AuthenticationException`), the same path an unmet `@RolesAllowed`
already uses, so it needs no `@WrapBusinessError` entry.

## Testing without a live Keycloak

`OrderMcpToolsTest` injects `OrderMcpTools` directly and drives it as a plain CDI bean, the same
approach ch15's `PaymentMcpToolsTest` uses: `@RolesAllowed`, the manual scope check and
`@WrapBusinessError` are all CDI interceptors, so they fire on a direct method call exactly as
they would on a call arriving over `/mcp`. `@TestSecurity` simulates the role; `@OidcSecurity`'s
`@Claim` simulates the token's `scope` claim. Neither needs a live Keycloak or Dev Services for
Keycloak: `%test.quarkus.oidc.tenant-enabled=false` and
`%test.quarkus.keycloak.devservices.enabled=false` keep a real IdP container from auto-starting.
`Elicitation` is a plain method parameter here, not something CDI injects, so the test passes a
Mockito stub of `Elicitation.requestBuilder()...sendAndAwait()` built by `respondingWith`, able to
answer ACCEPT or DECLINE without a server-initiated round trip.

## Build, run, observe

```bash
./verify.sh
```

A fresh stack comes up, an unauthenticated `/mcp` call is rejected, a `mcp:tools:read`-scoped
token can call `orderSummary` but not `cancelOrder` (the order is left `PLACED`), and a
`mcp:tools:write`-scoped token clears that same gate and reaches the `Elicitation` confirm (the
confirm round trip itself is exercised by `OrderMcpToolsTest`, not this script: see verify.sh's
trailing comment for why driving a server-initiated elicitation over plain curl is impractical).
The stack is torn down after. Set `KEEP=1` to leave it running.

- Try fetching a token with both `scope=mcp:tools:read mcp:tools:write` and see `cancelOrder`
  reach the same elicitation wait a write-only token does.
- Try calling `orderSummary` for an order id that does not exist and see the instructive tool
  error `@WrapBusinessError` produces.
- Try decoding an issued token (for example at jwt.io) and compare its `scope` and
  `realm_access.roles` claims to `contracts/16-mcp-scopes.md`.
