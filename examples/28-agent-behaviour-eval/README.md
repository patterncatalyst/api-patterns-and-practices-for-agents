# 28 Agent-behaviour eval with deterministic replay

A deterministic eval harness that turns an agent's behaviour on a task into a repeatable PASS or
FAIL check, without depending on live-model nondeterminism. The task is a cancel-and-refund on the
order-to-delivery shipping domain, reusing the shared `Order`, `Payment`, `OrderStatus` and
`PaymentStatus` records from `examples/domain-model`. A direct `quarkus-langchain4j-ollama`
`@RegisterAiService` (`AgentBehaviourAssistant`) with one `@Tool` (`CancelAndRefundTools`) performs
the task on the LIVE path only; `BehaviourEvaluator`, a deterministic bean, loads the committed
eval spec and scores a recorded transcript to `PASS` or `FAIL`, naming the one assertion that
failed; `EvalRecorder` writes a durable `eval.eval_runs` row, increments a Micrometer counter and
logs a line.

`BehaviourEvaluator` asserts only captured, structured signals, never a model's own prose:

- the captured tool-call sequence, the tool names a run actually called, read from
  `dev.langchain4j.service.Result<String>.toolExecutions()` on the LIVE path (filtering by
  `execution.request().name()`, reading `.result()` and `.request().arguments()`) or from a
  `tool_call` line in a committed transcript on the replay path
- the durable outcome: whether the refund actually completed, and the order's final status
- a call budget (at most `maxToolCalls` tool calls, at most `maxArgErrors` argument errors)
- a token budget (at most `maxTotalTokens`), read from the transcript's `usage` line

Argument values (a refund reason, for example) are captured in the transcript for the audit
record but never asserted literally: free text varies even on a fully compliant run. What is
asserted is which tools were called, how many times, whether any argument errors happened, how
many tokens were spent, and what actually happened to the order.

## The eval spec

The committed decision this harness is about is `contracts/28-behaviour-eval.json`, loaded once at
startup by `BehaviourEvaluator` (`agent.eval.spec.path` names the file, the same demo-code
convention 26-decision-rule-review's `review.rule.path` uses):

```json
{
  "evalId": "28-behaviour-eval",
  "expectedToolSequence": ["cancelAndRefund"],
  "maxToolCalls": 1,
  "maxArgErrors": 0,
  "maxTotalTokens": 900,
  "requireRefundCompleted": true,
  "requireFinalOrderStatus": "CANCELLED"
}
```

`BehaviourEvaluator.evaluate` checks six assertions in order against a `Transcript`; the first one
a transcript fails is the recorded verdict's `violatedAssertion`:

1. `unexpected-tool-called`: every captured tool call's name is one of `expectedToolSequence`
2. `call-budget-exceeded`: the number of tool calls exceeds `maxToolCalls`
3. `arg-error-present`: more than `maxArgErrors` calls were flagged as an argument error
4. `token-budget-exceeded`: `totalTokens` exceeds `maxTotalTokens`
5. `refund-not-completed`: `refundCompleted` does not match `requireRefundCompleted`
6. `final-status-mismatch`: `finalOrderStatus` does not match `requireFinalOrderStatus`

A transcript that fails none of the six is `PASS`. There is no weakened variant of the spec: the
negative witness here is the SAME spec scoring two different transcripts to opposite verdicts (see
below), not a weakened rule file swapped in like 26-decision-rule-review's.

## The transcript format

A recorded run is a small JSON-lines file, one JSON object per line, a `type` field picking its
shape:

| `type` | Count | Fields |
|--------|-------|--------|
| `run` | once, first | `runId`, `orderId`, `customerId` |
| `tool_call` | zero or more, in order | `name`, `args` (an object), `argError` (boolean) |
| `usage` | once | `inputTokens`, `outputTokens`, `totalTokens` |
| `outcome` | once, last | `finalOrderStatus`, `refundCompleted` (boolean) |

`TranscriptReader` parses this format into a `Transcript` record; `LiveRunner` builds the identical
shape from a live `Result<String>` on the LIVE path. `BehaviourEvaluator` scores either the same
way. Two committed, recorded transcripts live under `src/main/resources/fixtures/`:

`good-run.jsonl`, a compliant run: one `cancelAndRefund` call, no argument error, within the token
budget, the refund actually completed and the order reached `CANCELLED`. Scores `PASS`.

`regressed-run.jsonl`, a regression: three `cancelAndRefund` calls (the first with an empty
`orderId`, flagged `argError`), over budget on tokens, and the order never left `PAID`, the refund
never completed. Scores `FAIL`, naming `call-budget-exceeded`, the first of its several problems
the ordered assertions reach.

## Endpoints

Three Camel platform-http routes (`EvalRoute`), the same convention 26-decision-rule-review's
`ReviewRoute` uses. No REST framework.

`POST /eval` scores whatever JSON-lines transcript text is posted as the body: a caller's own
transcript, or a mutated copy of one of the committed fixtures (`verify.sh`'s mutation witness).

`GET /eval/run/good` is the MODEL=off reconciliation point. `agent.eval.model-source` (demo code,
like 24-langchain4j-programmatic-consumer's `agent.consumer.model-source`) picks the path:
`fixture`, the default everywhere and forced again in the `%test` profile, replays the committed
`good-run.jsonl`, so `mvn -B verify` and the default `verify.sh` lane never need Ollama. `ollama`,
set only by `verify.sh`'s `LIVE=1` lane, drives the real model through `LiveRunner` instead and
scores whatever it actually did.

`GET /eval/run/regressed` always replays the committed `regressed-run.jsonl`: a regression is a
hand-recorded transcript standing in for a bad run, never something the live model is asked to
reproduce.

## Run

```bash
./verify.sh
```

`verify.sh` needs Docker Engine with the compose plugin; it builds this service and the shared
domain model inside its image, starts PostgreSQL and LGTM (plus Kafka, Apicurio, Keycloak and
Ollama, this book's shared infrastructure, unused by the default lane but still brought up and
health-checked), scores the good and regressed transcripts, checks OTel signals reach Tempo,
Prometheus and Loki, runs the mutation witness, and removes everything with `down -v`. Set `KEEP=1`
to leave the stack running for inspection. Set `LIVE=1` to drive `GET /eval/run/good` against the
real pinned `qwen3:4b-instruct-2507-q4_K_M` model (temperature 0) instead of replaying
`good-run.jsonl`, scoring a freshly-recorded transcript against the same spec and budgets; the
shared Ollama model volume must already be pulled (`../../scripts/ollama-model.sh pull`).

Run the tests alone (Quarkus Dev Services starts PostgreSQL; no compose stack and no model
needed). Install the shared domain model first:

```bash
(cd ../domain-model && mvn -B -q install)
mvn -B verify
```

## Tests and checks in detail

`AgentBehaviourEvalTest` (`mvn -B verify`) exercises the whole trigger-to-record path against Dev
Services PostgreSQL with `agent.eval.model-source` forced to `fixture`: the good transcript scores
`PASS` with no violated assertion, the regressed transcript scores `FAIL` naming
`call-budget-exceeded`, and posting the raw good fixture text to `POST /eval` records the same
result. A unit-level case then edits the good transcript's own `refundCompleted` flag (`PASS` to
`FAIL`, naming `refund-not-completed`) and adds an extra tool call (`PASS` to `FAIL`, naming
`call-budget-exceeded`), proving the evaluator's `PASS` is not vacuous. No model is reachable from
this path at all, so none of these assertions is on model behaviour.

`verify.sh` adds the live view: the same two transcripts scored over HTTP against the real compose
stack, durable verdicts observed in PostgreSQL, OTel signals reaching Tempo, Prometheus and Loki,
and the mutation witness, live, against the running container: the same committed spec scores the
good transcript `PASS`, then scores a mutated copy of the exact same transcript `FAIL` once its
`refundCompleted` flag is flipped, naming `refund-not-completed`, before the real, unmutated
transcript is resubmitted and passes again.

## Layout

| Path | Purpose |
|------|---------|
| `src/main/java/.../EvalRoute.java` | the triggers, `POST /eval`, `GET /eval/run/good`, `GET /eval/run/regressed` |
| `src/main/java/.../EvalBean.java` | the bridge from a route to the evaluator and the recorder |
| `src/main/java/.../Transcript.java`, `TranscriptToolCall.java` | the recorded run this harness scores |
| `src/main/java/.../TranscriptReader.java` | parses the committed JSON-lines transcript format |
| `src/main/java/.../FixtureTranscripts.java` | reads a committed fixture from the classpath |
| `src/main/java/.../EvalSpec.java` | the eval spec as data, read from the committed JSON |
| `src/main/java/.../BehaviourEvaluator.java` | the deterministic scorer; `agent.eval.spec.path` picks the spec file |
| `src/main/java/.../EvalVerdict.java` | `PASS`/`FAIL` plus the violated assertion, if any |
| `src/main/java/.../EvalRecorder.java` | the durable write, the Micrometer counter, the log line |
| `src/main/java/.../AgentOrderService.java`, `AgentOrderException.java` | the LIVE path's in-memory seeded order |
| `src/main/java/.../CancelAndRefundTools.java`, `AgentBehaviourAssistant.java` | the LIVE path's one tool and AiService |
| `src/main/java/.../LiveRunner.java` | drives the LIVE task once and builds a `Transcript` from the result |
| `src/main/resources/fixtures/` | the committed, recorded good and regressed transcripts |
| `src/main/resources/db/migration/` | Flyway migration for `eval.eval_runs` |
| `contracts/28-behaviour-eval.json` | the committed eval spec (repo root) |
| `compose.yaml` | includes the shared infrastructure, adds this service, bind-mounts `contracts/` |
| `Containerfile` | UBI 10 multi-stage build; context is `examples/` |
| `verify.sh` | the checks above |
