# 01 Why agent APIs

A Quarkus service that proves chapter 01's thesis with a measured contrast: the same task, "cancel order O-1007 and refund the customer," given to the same local langchain4j `AiService` (backed directly by `quarkus-langchain4j-ollama`, not the Camel langchain4j-agent component) against two different tool contracts for the same business operation (`OrderOpsService`).

The **bad contract** (`BadOrderOpsTools`) is three granular, CRUD-ish tools: `getOrder`, `updateOrderStatus`, `createRefund`. None of them know about the others, and every failure is a bare opaque code (`{"error":"E400"}`) with no remedy text, so a caller that does not already know the right sequence has to discover it by trial and error. The **good contract** (`GoodOrderOpsTools`) is one intent tool, `cancelAndRefund(orderId, reason)`, that does the whole job and, on failure, says exactly what is wrong (for example `order O-9999 does not exist`).

`RunResource` (`GET /run/good`, `GET /run/bad`) drives one live run of the task against each contract and reports what it cost: tool calls, argument errors, token usage (from langchain4j's `Result.tokenUsage()`), and whether the refund actually completed. `AssistantRunner` resets the seeded order before each run, so both contracts always start from the same state (order `O-1007`, status PAID, amount 129.99). Every tool call and argument error is also counted with a Micrometer counter (`whyagentapis.tool.calls`, `whyagentapis.tool.arg_errors`, tagged `contract=good|bad`) and logged.

## The measured contrast

A transcript was recorded once against the pinned model (`qwen3:4b-instruct-2507-q4_K_M`, `_plans/versions.md`) and committed under `fixtures/`. `verify.sh` replays it by default, so CI never needs a model or a container. Set `LIVE=1` to run the same task against the real model instead.

| | Good contract | Bad contract |
|---|---|---|
| Tool calls | 1 | 5 |
| Argument errors | 0 | 2 |
| Tokens (input + output) | 692 + 67 = 759 | 2717 + 468 = 3185 |
| Refund actually completed | yes | **no** |

The recorded bad-contract run is not just slower: the agent called `updateOrderStatus`, then guessed a refund amount (`25.50`) and got an opaque `E400`, called `getOrder` to find the real amount, called `createRefund` again but this time without an `orderId`, got a second opaque `E400`, called `getOrder` again, and then gave up, telling the user the refund "cannot be fulfilled due to system limitations." The order was cancelled but the customer was never refunded. The good contract's single call succeeded cleanly in one step. Full tool traces for both runs are in `fixtures/good-run.json` and `fixtures/bad-run.json`.

## Run

```bash
./verify.sh                 # replay the recorded transcript; no Docker, no model
LIVE=1 ./verify.sh           # run the same task against the real Ollama model
```

`LIVE=1` needs Docker Engine with the compose plugin and the pulled Ollama model (`../../scripts/ollama-model.sh pull`, once); it brings up a minimal stack (Ollama plus this service; no Kafka, PostgreSQL, Apicurio or Keycloak, since this example needs none of them), builds the service inside the image, calls `/run/good` and `/run/bad` against the running model, and removes everything with `down -v` unless `KEEP=1`.

Run the deterministic tests alone (no model, no containers; installs the shared domain model first):

```bash
(cd ../domain-model && mvn -B -q install)
mvn -B verify
```

## Expected output (replay, the default)

```
Replaying the recorded transcript in fixtures/ (set LIVE=1 to run the real model instead):
  good: toolCalls=1 argErrors=0 totalTokens=759 refundCompleted=true
  bad: toolCalls=5 argErrors=2 totalTokens=3185 refundCompleted=false
PASS 1: good contract completes the task in exactly 1 tool call
PASS 2: good contract raises 0 argument errors
PASS 3: good contract actually completes the refund
PASS 4: bad contract needs more tool calls than the good contract (5 > 1)
PASS 5: bad contract spends more tokens than the good contract (3185 > 759)
PASS 6: negative witness: the good contract's one-call rule fails when applied to the bad contract's run
6/6 checks passed
```

The negative witness (check 6) takes the good contract's own pass rule, exactly one tool call, and applies it to the bad contract's recorded run: it fails, because the bad run took 5 calls. That proves the rule actually discriminates between the two contracts instead of always passing.

`LIVE=1` reproduced the same numbers in the recording session for this example (temperature 0 and a fixed seed, see `application.properties`); a different run of qwen3 may vary, since tool calling is nondeterministic. If a LIVE run does not clear the gates above, that is itself evidence for the chapter: a model's tool-calling choices on the bad contract are not even reliably reproducible, let alone efficient.

## How the fixture was recorded

With the stack up (`LIVE=1` without tearing down, or by hand): `curl http://localhost:8080/run/good` and `curl http://localhost:8080/run/bad`, each exactly once, with the pinned model and `temperature=0`, `seed=42`. The two JSON responses were saved verbatim as `fixtures/good-run.json` and `fixtures/bad-run.json`. No field was edited after recording.

## Tests and checks in detail

`WhyAgentApisTest` (`mvn -B verify`) exercises `OrderOpsService` and both tool classes directly, never the assistant, so it needs no model and no network: the good contract's single call succeeds and its errors name the problem (`O-9999 does not exist`); the bad contract needs all three tools to do the same job and returns a bare `{"error":"..."}` for a missing order, a mismatched refund amount, or an unrecognized status spelling (`CANCELED` instead of `CANCELLED`).

The measured, model-driven contrast above is a separate concern from these deterministic tests: it is captured once in `fixtures/` and asserted by `verify.sh`.

## Experiments

| Switch | Effect | Failing checks |
|---|---|---|
| Edit `fixtures/bad-run.json` so `toolCalls` is `1` | The bad contract no longer costs more calls than the good one, and the negative witness no longer discriminates between them | 4 and 6 |
| Edit `fixtures/good-run.json` so `refundCompleted` is `false` | The good contract no longer proves it completed the task | 3 |
| Edit `fixtures/bad-run.json` so `totalTokens` is `100` (below the good contract's 759) | The token-cost contrast no longer holds | 5 |

## Layout

| Path | Purpose |
|------|---------|
| `src/main/java/.../OrderOpsService.java` | the one business operation both contracts drive; the seeded order and its lifecycle |
| `src/main/java/.../OrderOpsException.java` | a failure's opaque code and its instructive message, in one place |
| `src/main/java/.../GoodOrderOpsTools.java`, `BadOrderOpsTools.java` | the two tool contracts, each with its own Micrometer counters |
| `src/main/java/.../GoodContractAssistant.java`, `BadContractAssistant.java` | the langchain4j AiServices, identical except for their registered tools |
| `src/main/java/.../AssistantRunner.java`, `RunResource.java` | drives one LIVE run per contract and reports the measured outcome |
| `src/main/java/.../RunOutcome.java` | the shape shared by a LIVE run's JSON response and the committed fixtures |
| `fixtures/good-run.json`, `fixtures/bad-run.json` | the recorded transcript; replayed by `verify.sh` by default |
| `compose.yaml` | Ollama only (LIVE mode); no Kafka, PostgreSQL, Apicurio or Keycloak |
| `Containerfile` | UBI 10 multi-stage build; context is `examples/` |
| `verify.sh` | replays the fixture by default; `LIVE=1` runs the real model instead |
