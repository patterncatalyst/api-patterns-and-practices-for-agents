# 35 Identity and authorization guardrails

Extends a bearer-gated MCP surface with two more checks stacked on top of
role and scope: an RFC 8693 token exchange for stepping a caller up to a
stronger credential, and a by-hand floor on that credential's `acr`
(Authentication Context Class Reference) claim.

1. **OIDC bearer on `/mcp`.** The whole endpoint requires a verified bearer
   token from the `apa` Keycloak realm (`examples/_infra/keycloak/realm-apa.json`):
   `quarkus.http.auth.permission.mcp.paths=/mcp` with
   `policy=authenticated` rejects an unauthenticated call before the MCP
   dispatcher ever runs.
2. **Role on the read.** `OrderMcpTools.orderSummary` carries
   `@RolesAllowed("agent")`. No scope or acr check: a low-assurance,
   read-scoped token can call it.
3. **Scope on the write.** `cancelOrder` checks the token's `scope` claim by
   hand for `mcp:tools:write`, the same `JsonWebToken` pattern the
   securing-and-gating MCP pattern uses, and for the same reason:
   `@PermissionsAllowed`'s annotation value splits on the first `:`, which
   collides with a colon-separated scope name.
4. **Step-up acr on the write.** `cancelOrder` also checks the token's `acr`
   claim by hand against a required step-up value. A token that was never
   exchanged carries no `acr` claim at all; a missing claim and a claim with
   any other value both fail the floor.

## The realm additions

Additive to the shared `apa` realm, every existing client, role and scope
untouched:

- `apa-agent-bff`: a confidential client with `standard.token.exchange.enabled: true`
  and `serviceAccountsEnabled: true`, so it can both fetch its own
  low-assurance token and act as the RFC 8693 requester that exchanges it.
- `step-up-acr`: an optional client scope on `apa-agent-bff` carrying two
  protocol mappers, applied only when requested in the exchange call: a
  hardcoded `acr` claim mapper (the step-up value) and an audience mapper
  naming `apa-mcp`.

See `contracts/35-token-exchange-flow.md` for the exact request, confirmed
live against Keycloak 26.8.2.

## Why the step-up floor is by-hand, not a Quarkus-OIDC native mechanism

quarkus-oidc 3.40.1.2 in bearer (service) mode has no built-in step-up or
acr-floor primitive to declare on a method; the same
`org.eclipse.microprofile.jwt.JsonWebToken` injection the scope check
already uses reads the `acr` claim directly. `STEPUP_ENFORCED=false`
disables only this one check (the scope check is a separate axis and
always runs), letting both the live run and `StepUpDisabledTest` prove the
acr floor, specifically, is what blocks an under-assured write.

## Testing without a live Keycloak

`OrderMcpToolsTest` injects `OrderMcpTools` directly and drives it as a
plain CDI bean: `@RolesAllowed` and the role interceptor fire on a direct
method call exactly as they would on a call arriving over `/mcp`.
`@TestSecurity` simulates the role; `@OidcSecurity`'s `@Claim` simulates
the token's `scope` and `acr` claims, the same claims a real RFC 8693
exchange stamps onto a stepped-up token, with no live Keycloak container
or Dev Services for Keycloak. `StepUpDisabledTest` runs the same tool
under a `QuarkusTestProfile` that overrides `guardrails.step-up.enforced`
to `false`, the in-JVM form of the live negative witness.

## Build, run, observe

```bash
./verify.sh
```

A fresh stack comes up, an unauthenticated `/mcp` call is rejected, a
low-assurance read-scoped token can call `orderSummary` but not
`cancelOrder` (the target order is left unchanged), a real RFC 8693
exchange trades that token for a write-scoped, step-up acr token, and that
exchanged token clears both gates and cancels the order. The stack is torn
down after. Set `KEEP=1` to leave it running.

- Try exchanging for `step-up-acr` alone, without `mcp:tools:write`, and
  see `cancelOrder` still reject it on scope.
- Try calling `orderSummary` for an order id that does not exist and see
  the instructive tool error.
- Try decoding the exchanged token (for example at jwt.io) and compare its
  `acr`, `scope` and `aud` claims against `contracts/35-token-exchange-flow.md`.
