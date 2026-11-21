# Appendix F: Deterministic decisioning behind an agent tool

The concrete answer to the Prompt-as-Business-Logic anti-pattern: the model proposes an action,
and a committed rules engine, not the model, decides whether it takes effect. This appendix
reimplements ch26-decision-rule-review's refund-approval decision, unchanged in substance, as an
Apache Drools ruleset fired from behind an MCP tool. The domain is the same order-to-delivery
shipping domain: an agent proposes a refund for an order (an amount and a reason), and the
committed rule decides `APPROVE` or `REJECT` from that proposal plus the order's own facts, its
paid amount and its status. The same rule, committed as a DRL file, produces the same verdict
every time, independent of any model.

`reviewRefund`, an MCP tool (`quarkus-mcp-server-http`, the `/mcp` Streamable HTTP endpoint),
takes a proposal exactly as an agent would state it: `orderId`, `customerId`, `orderStatus`,
`orderPaidAmount`, `refundAmount`, `reason`. `RefundReviewTool` builds a `RefundProposal` fact from
those arguments and hands it to `RefundReviewer`, which compiles `rules/refund-review.drl` once at
startup with Drools's `KieHelper` (a plain library, no Kogito, no DMN, no `quarkus-drools`
extension) and fires it in a fresh `KieSession` per call. The ruleset's three rules are identical
in substance and in id to ch26's `contracts/26-refund-review-rule.json`: `amount-exceeds-paid` (the
refund must not exceed the order's paid amount), `order-not-refundable` (the order must be `PAID`
or `SHIPPED`, never already `CANCELLED` or `DELIVERED`) and `reason-not-allowed` (the stated reason
must be one of an allowed set). `salience` orders the three rules so the first one a proposal fails
is the recorded verdict's `failedClause`; a proposal that fails none stays `APPROVE`.
`ReviewRecorder` then writes a durable `refunds.reviews` row (the order id, the proposal and the
verdict), increments a Micrometer counter and logs a line, so a review done by the rule engine is
observable without re-reading its own trace.

No model is ever on this path. `verify.sh` drives the tool directly over the MCP protocol with two
proposals standing in for what an agent would otherwise propose live: a compliant one (reviewed to
`APPROVE`) and an over-limit one (reviewed to `REJECT` with `failedClause: amount-exceeds-paid`).
Since `RefundReviewer` is deterministic by construction, there is nothing a model could contribute
to this example's own assertions that those two proposals do not already give it.

## The weakened-ruleset witness

`rules/refund-review-weakened.drl` is the same three rules with `amount-exceeds-paid` removed,
committed only for this witness and never the default. Both DRL files ship inside the built image
(`src/main/resources/rules/`), so no bind mount is needed to swap between them.
`review.rule.resource` (demo code, like ch26's `review.rule.path`) names the classpath DRL resource
`RefundReviewer` compiles at startup; compose exposes it as `REVIEW_RULE_RESOURCE`, overridable per
container recreate. Pointed at the weakened resource and asked to review the exact same over-limit
proposal again, the reviewer wrongly approves it, proving the removed rule is exactly what was
catching the over-refund. Restoring the default resource and replaying the identical proposal
rejects it again, naming the same clause. `RefundReviewRulesTest` reproduces the same comparison at
the unit level, calling the evaluator directly against both rule resources with no container
involved; that test is also where `DroolsSpikeTest`'s de-risking spike (a trivial DRL rule, loaded
from an explicit classpath resource and fired with `KieHelper`, proving runtime DRL compilation
works on this JDK before any of the real rules were written) leads.

## Run

```bash
./verify.sh
```

`verify.sh` needs Docker Engine with the compose plugin; it builds this service and the shared
domain model inside its image, starts PostgreSQL and LGTM (plus Kafka, Apicurio, Keycloak and
Ollama, this book's shared infrastructure, unused by this example but still brought up and
health-checked), drives the `reviewRefund` MCP tool over Streamable HTTP for both proposals, checks
the recorded verdicts in PostgreSQL, confirms metrics and logs in the observability stack, then
runs the weakened-ruleset negative witness and removes everything with `down -v`. Set `KEEP=1` to
leave the stack running for inspection.

`quarkus-mcp-server-http`'s Streamable HTTP endpoint is a raw Vert.x route, not auto-instrumented
for distributed tracing the way a Camel or `quarkus-rest` route is: Prometheus and Loki both
receive signals from this service, but Tempo receives no trace from an MCP tool call. ch13
(`package-api-as-mcp-tools`, the other MCP-tool-only example in this book, with no Camel route)
holds the same line and checks no Tempo signal either.

Run the tests alone (Quarkus Dev Services starts PostgreSQL; no compose stack and no model
needed). Install the shared domain model first:

```bash
(cd ../domain-model && mvn -B -q install)
mvn -B verify
```

## Tests and checks in detail

`RefundReviewRulesTest` (`mvn -B verify`) exercises the committed DRL directly, with no CDI and no
container: a compliant proposal reaches `APPROVE`, an over-limit one reaches `REJECT` naming
`amount-exceeds-paid`, each of the other two clauses is independently provable, a proposal that
fails all three rules is rejected naming the highest-salience one (`amount-exceeds-paid`), and the
weakened ruleset approves that same over-limit proposal. `RefundReviewToolTest` exercises the whole
tool-to-record path against Dev Services PostgreSQL. `DroolsSpikeTest` is the de-risking spike that
ran first: a trivial DRL rule, loaded from an explicit classpath resource and fired with
`KieHelper`, proving runtime DRL compilation works on this JDK (25) before any of the real rules
were written. No model is reachable from any of these paths, so none of these assertions is on
model behaviour.

`verify.sh` adds the live view: the same two proposals reviewed over the MCP protocol against the
real compose stack, OTel signals reaching Tempo, Prometheus and Loki, and the weakened-ruleset
negative witness, container-level, with `REVIEW_RULE_RESOURCE` actually pointed at the weakened
resource and then restored.

## Layout

| Path | Purpose |
|------|---------|
| `src/main/java/.../RefundReviewTool.java` | the agent-facing surface, the `reviewRefund` MCP tool |
| `src/main/java/.../RefundProposal.java` | the Drools fact: the proposal plus the order's facts |
| `src/main/java/.../RefundReviewer.java` | compiles the committed DRL once with `KieHelper`; fires it per proposal |
| `src/main/java/.../Verdict.java` | the Drools fact a rule's consequence writes to: `APPROVE`/`REJECT` plus the failed clause |
| `src/main/java/.../ReviewRecorder.java` | the durable write, the Micrometer counter, the log line |
| `src/main/resources/rules/refund-review.drl` | the committed Drools ruleset (three rules, salience-ordered) |
| `src/main/resources/rules/refund-review-weakened.drl` | the weakened variant, negative witness only |
| `src/main/resources/fixtures/` | the committed compliant and violating proposals |
| `src/main/resources/db/migration/` | Flyway migration for `refunds.reviews` |
| `src/test/java/.../DroolsSpikeTest.java` | the de-risking spike: a trivial DRL rule fires on this JDK |
| `src/test/java/.../RefundReviewRulesTest.java` | the real ruleset, unit-level, including the weakened-ruleset witness |
| `src/test/java/.../RefundReviewToolTest.java` | the tool-to-record path against Dev Services PostgreSQL |
| `compose.yaml` | includes the shared infrastructure, adds this service |
| `Containerfile` | UBI 10 multi-stage build; context is `examples/` |
| `verify.sh` | the checks above |
