---
title: "Identity and Authorization Guardrails"
order: 35
part: "Run in production"
description: "A destructive tool checks how strongly a caller's credential was obtained, not only who is calling and what role it carries."
duration: "25 minutes"
---

## Learning objectives

- Require a verified bearer token and a role on a read, the same floor any credentialed MCP surface needs.
- Add a scope requirement and a step-up assurance floor to a destructive write, proving authority and assurance as two separate checks.
- Trade a low-assurance token for a stronger one through a standards-based token exchange rather than minting a credential out of band.
- Recognize a scope check standing in for an assurance check, and an assurance claim nobody verifies, as guards that look present but gate nothing.

## Why this matters

A caller invoking a destructive tool with the right role and even the right scope has only proven who is calling and what it is permitted to do, never how strongly that credential was obtained. A token minted once, at the start of a long-lived session, can carry a write scope indefinitely without the caller ever proving anything beyond a single low-friction login. A security review that asks whether a destructive action could have been taken by a credential nobody ever stepped up has no good answer unless the operation itself checks the strength of the presented credential, not only its role and scope.

{% include excalidraw.html file="35-token-exchange-step-up" alt="A low-assurance, read-scoped token calls the read tool but is rejected by the destructive tool; a token exchange trades it for a write-scoped, step-up acr token that clears both gates" caption="Figure 35.1: A stronger credential, not a stronger caller, is what clears the destructive gate" %}

## Pattern: Identity and Authorization Guardrails

### The problem

A role and a scope answer who is calling and what it may do, but neither says anything about how the caller obtained its credential. A caller that authenticated once, lightly, and has carried the same long-lived token ever since looks identical, on role and scope alone, to one that just cleared a stronger authentication step. Gating a destructive operation on role and scope alone trusts every caller at that same, lowest bar, regardless of how the token in hand was actually earned.

### The solution

**A destructive operation checks a token's assurance, its acr (Authentication Context Class Reference) claim, on top of its role and scope, and a caller that wants to act rather than only read trades its current token for one carrying that stronger assurance through a standards-based token exchange, never a second credential minted out of band.**

### How Quarkus models it

The read tool stays exactly as light as any role-gated read: a verified bearer token and a role, nothing else.

```java
@RolesAllowed("agent")
public String orderSummary(String orderId) { ... }
```

The destructive tool stacks two checks on top of that same role, both read by hand from the injected `JsonWebToken` rather than through a convenience annotation: a scope, and an assurance floor.

```java
public String cancelOrder(String orderId, String reason) {
    requireWriteScope();
    requireStepUpAcr();
    OrderRepository.OrderRow order = orders.find(orderId);
    if (!order.isCancellable()) throw new OrderNotCancellableException(orderId, order.status());
    orders.cancelAndRefund(orderId);
    return toJson(CancelOrderResult.cancelled(orderId, order.amount()));
}

private void requireStepUpAcr() {
    if (!stepUpEnforced) return;
    String acr = accessToken.getClaim("acr");
    if (!REQUIRED_STEP_UP_ACR.equals(acr)) {
        throw new ForbiddenException("the current token's acr claim does not meet the step-up "
                + "assurance level required to cancel an order");
    }
}
```

Both checks throw `ForbiddenException`, the same security failure the MCP runtime already recognizes on its own, so neither needs extra wrapping. The assurance claim itself is never minted by this application: a caller that only holds a read-scoped, low-assurance token performs an RFC 8693 token exchange against the identity provider, trading that token for a new one whose issuing client stamps both the write scope and the step-up `acr` value onto it. The exchange happens entirely between the caller and the identity provider; this code only ever reads the claims a verified token already carries, the same discipline the scope check uses, extended to assurance. A flag disables only the assurance check, leaving the scope check untouched, which is how both a live run and an in-process test isolate the assurance floor as the thing standing between a write-scoped token and a mutation it should not yet be trusted to make.

> **🤖 Explore with your agent**
> **Intent (stack-neutral):** a destructive operation must require a stronger proof of authentication than a read, on top of whatever role and scope already gate it, and that stronger proof must come from the identity provider, not from the calling application.
> **Contract shape:** operation intent "read" (role-gated) versus "act" (role-, scope- and assurance-gated); inputs an identifier plus an action reason; response shape the read's own data versus the action's outcome; error shape forbidden for a missing role, scope or assurance level, each distinguishable from the others; idempotency key not needed, an assurance check is evaluated fresh on every call.
> **Build it here:** "Using `OrderMcpTools.cancelOrder` as the model, add a second destructive tool gated by a different acr value, and confirm a token stepped up for one action cannot reach the other."
> **Build it elsewhere:** "Implement the same pattern in Python: a FastAPI service behind an MCP server, checking a bearer token's role and scope for authority and its acr claim for assurance, with the stronger credential obtained only through an OAuth 2.0 token exchange against the identity provider, never minted locally."
> Check: a token missing the write scope is rejected regardless of its assurance level, a token missing the assurance level is rejected regardless of its scope, and only a token carrying both ever reaches the mutation.

## Build, run, observe

```bash
./verify.sh
```

Run from the identity-and-authorization-guardrails example, this brings up the stack with the identity provider, confirms an unauthenticated call is rejected, confirms a low-assurance token can read but not cancel, performs a real token exchange, and confirms the exchanged token clears both gates and cancels the order. It then recreates the application with the assurance check disabled, confirms a write-scoped but never-stepped-up token now reaches the mutation, and restores the check before a final pass. It ends at 24/24.

Try:

- Exchange for the assurance scope alone, without the write scope, and see the cancellation still rejected on scope.
- Call the read tool for an order id that does not exist and see the instructive tool error.
- Decode the exchanged token and compare its acr, scope and audience claims against the committed exchange contract.

## Anti-patterns

- **Treating a long-lived scope as proof of present assurance.** A token's scope says what it is allowed to do once obtained; it says nothing about how recently or how strongly the caller actually authenticated to obtain it.
- **An acr claim nobody verifies.** Configuring an identity provider to stamp an assurance claim onto a token, then never checking it in the application the claim was meant to protect, is a guard that looks present and gates nothing.
- **Minting a stepped-up credential out of band.** Issuing a second, separately generated token for a privileged action, rather than exchanging the caller's own verified token for a stronger one, breaks the chain back to the original authentication and gives an attacker a second credential-minting path to attack.

## When not to use it

A low-stakes, easily reversible write that is already audited elsewhere gains little from a separate assurance floor on top of its scope: the extra round trip to exchange a token costs a caller latency and complexity a trivial action does not need. Reach for a step-up assurance floor when the action is destructive enough, or costly enough to reverse, that the application needs evidence the caller proved more than a routine login before it acts.

## References

- RFC 8693: OAuth 2.0 Token Exchange: [rfc-editor.org/rfc/rfc8693](https://www.rfc-editor.org/rfc/rfc8693.html)
- RFC 9470: OAuth 2.0 Step Up Authentication Challenge Protocol: [rfc-editor.org/rfc/rfc9470](https://www.rfc-editor.org/rfc/rfc9470.html)
- OpenID Connect Core 1.0: [openid.net/specs/openid-connect-core-1_0.html](https://openid.net/specs/openid-connect-core-1_0.html)
- Keycloak documentation: [keycloak.org/documentation](https://www.keycloak.org/documentation)
