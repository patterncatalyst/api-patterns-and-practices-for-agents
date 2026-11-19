# 26 Prompt-plus-contract loop and decision-rule review

The Part 4 pattern that closes the loop: an agent proposes an action under a contract, and a
deterministic reviewer gates it before it takes effect. The reviewer is the lesson, not the agent.
The domain is refund approval, reusing the order-to-delivery shipping domain: an agent proposes a
refund for an order (an amount and a reason), and a committed decision rule decides `APPROVE` or
`REJECT` from that proposal plus the order's own facts, its paid amount and its status. The same
rule, committed as data, produces the same verdict every time, independent of any model: "Model
Reasons, Application Controls" made concrete.

`POST /review`, a Camel platform-http route (`ReviewRoute`, the same convention ch24's
`ConsumeOrderStatusRoute` uses), takes a recorded agent run as its JSON body: the proposal
(`refundAmount`, `reason`) and the order's facts (`orderStatus`, `orderPaidAmount`) together, as
`RefundProposalRun`. `ReviewBean`, a thin bridge, hands it to `DecisionRuleReviewer`, which
evaluates it against `contracts/26-refund-review-rule.json`, the committed decision rule as data,
loaded once at startup. The rule is an ordered list of clauses; each is evaluated by a plain switch
on its `type`, the committed rule's real three being `amountNotGreaterThanPaid` (the refund must
not exceed the order's paid amount), `orderStatusIn` (the order must be `PAID` or `SHIPPED`, never
already `CANCELLED` or `DELIVERED`) and `reasonIn` (the stated reason must be one of an allowed
set). The first clause a proposal fails is the recorded verdict's `failedClause`; a proposal that
fails none is `APPROVE`. This is a hand-rolled JSON-rule evaluator, not Drools or DMN:
Appendix F builds this exact same refund-review decision again as a Drools/DMN ruleset, the
rule-engine sibling of this chapter's plain Java switch. `ReviewRecorder` then writes a durable
`review.reviews` row (the order id, the full proposal as JSON, the verdict and, on `REJECT`, the
failed clause), increments a Micrometer counter and logs a line, so a review is observable
independent of which rule file produced it.

No model is ever on this path. The proposals this example reviews are recorded agent runs, not a
live model call: two committed fixtures under `src/main/resources/fixtures/`, `compliant-run.json`
(an in-bounds refund, reviewed to `APPROVE`) and `violating-run.json` (an over-refund, reviewed to
`REJECT` with `failedClause: amount-exceeds-paid`), stand in for an agent's proposals and are
replayed by both the Quarkus test and `verify.sh`. Since `DecisionRuleReviewer` is deterministic by
construction, there is nothing a model could contribute to this example's own assertions that the
fixtures do not already give it; adding a real-model proposal step here would only reintroduce the
one thing this chapter's reviewer exists to make irrelevant.

## The weakened-reviewer witness

`contracts/26-refund-review-rule.weakened.json` is the same rule with its
`amountNotGreaterThanPaid` clause removed, committed only for this witness, never used as a
default. `review.rule.path` (demo code, like ch24's `MCP_APA_ORDERS_URL` and ch25's
`CONSUMER_ASYNC_ENABLED`) names the rule file `DecisionRuleReviewer` reads at startup; compose
exposes it as `REVIEW_RULE_PATH`, overridable per container recreate, and bind-mounts both rule
files read-only from the committed `contracts/` directory. Pointed at the weakened file and asked
to review the exact same violating proposal again, the reviewer wrongly approves it, proving the
removed clause is exactly what was catching the over-refund. Restoring the default path rejects it
again, naming the same clause. `DecisionRuleReviewTest` reproduces the same comparison at the unit
level, calling the evaluator directly against both rule files with no container involved.

## Run

```bash
./verify.sh
```

`verify.sh` needs Docker Engine with the compose plugin; it builds this service and the shared
domain model inside its image, starts PostgreSQL and LGTM (plus Kafka, Apicurio, Keycloak and
Ollama, this book's shared infrastructure, unused by this example but still brought up and
health-checked), runs the checks above (including the weakened-reviewer negative witness) and
removes everything with `down -v`. Set `KEEP=1` to leave the stack running for inspection.

Run the tests alone (Quarkus Dev Services starts PostgreSQL; no compose stack and no model
needed). Install the shared domain model first:

```bash
(cd ../domain-model && mvn -B -q install)
mvn -B verify
```

## Tests and checks in detail

`DecisionRuleReviewTest` (`mvn -B verify`) exercises the whole trigger-to-record path against Dev
Services PostgreSQL: the compliant fixture is recorded `APPROVE`, the violating fixture is recorded
`REJECT` naming the `amount-exceeds-paid` clause, and, at the unit level, the weakened rule
approves that same violating proposal. No model is reachable from this path at all, so none of
these assertions is on model behaviour.

`verify.sh` adds the live view: the same two recorded runs reviewed over HTTP against the real
compose stack, OTel signals reaching Tempo, Prometheus and Loki, and the weakened-reviewer negative
witness, container-level, with `REVIEW_RULE_PATH` actually pointed at the weakened file and then
restored.

## Layout

| Path | Purpose |
|------|---------|
| `src/main/java/.../ReviewRoute.java` | the trigger, `POST /review` |
| `src/main/java/.../ReviewBean.java` | the bridge from the route to the reviewer and the recorder |
| `src/main/java/.../RefundProposalRun.java` | the recorded run: the proposal plus the order's facts |
| `src/main/java/.../DecisionRule.java`, `RuleClause.java` | the decision rule as data, read from the committed JSON |
| `src/main/java/.../DecisionRuleReviewer.java` | the deterministic evaluator; `review.rule.path` picks the rule file |
| `src/main/java/.../Verdict.java` | `APPROVE`/`REJECT` plus the failed clause, if any |
| `src/main/java/.../ReviewRecorder.java` | the durable write, the Micrometer counter, the log line |
| `src/main/resources/fixtures/` | the committed, recorded compliant and violating runs |
| `src/main/resources/db/migration/` | Flyway migration for `review.reviews` |
| `contracts/26-refund-review-rule.json` | the committed decision rule (repo root) |
| `contracts/26-refund-review-rule.weakened.json` | the weakened variant, negative witness only (repo root) |
| `compose.yaml` | includes the shared infrastructure, adds this service, bind-mounts `contracts/` |
| `Containerfile` | UBI 10 multi-stage build; context is `examples/` |
| `verify.sh` | the checks above |
