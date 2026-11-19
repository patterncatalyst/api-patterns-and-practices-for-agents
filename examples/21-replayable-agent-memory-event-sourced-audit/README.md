# 21 Replayable agent memory, event-sourced audit

An append-only audit log of an agent's own actions, in both directions: an inbound tool call the
agent consumed (`direction=INBOUND`), and an outbound model call its langchain4j code made
(`direction=OUTBOUND`). The log is a Kafka topic, `apa.audit.agent-actions`
(`AuditTopics.AGENT_ACTIONS`); the projection is a schema-qualified PostgreSQL table,
`shipping.audit_log_projection`, kept current by `AuditProjectionRoute` folding the log as it
arrives. A replay endpoint rebuilds the same projection from scratch, by reading the log again
from the beginning, and the two must always agree, because the fold is a pure function of the
log: same records in, same projection out, every time.

`POST /audit/shipment-advice` is the one call a caller makes to the agent's one tool
(`{"orderId":"O-21001","identity":"agent:shipping-ui"}`): it appends the inbound record (the tool
call it just consumed), asks `AuditModelDecisionAdvisor` for a carrier decision, then appends the
outbound record (the model call, with its token counts and a cost derived from them). Both land
on the same topic, folded by the same route, into the same table.

## This chapter has no live model

Chapter 17's thesis is that an agent reacting to an event needs no poll loop, so its checks need
a live, nondeterministic model call to prove the round trip actually happens. This chapter's
thesis is different: the append-only log and its replay are a pure fold, and whether a replay
reproduces the live projection does not depend on what any model decided, only on the log being
read the same way twice. There is therefore no live Ollama path here to force off for tests
(contrast `agent.wakeup.model-source` in chapter 17): `AuditModelDecisionAdvisor` always replays
a committed, recorded decision from `src/main/resources/fixtures/model-decisions.jsonl`, falling
back to a fixed rule (FASTLINE) for any order id the fixture does not name. `mvn -B verify` and
the live `verify.sh` exercise the exact same code path.

`costUsd` is derived from `tokensIn` and `tokensOut` alone, at a fixed, published per-token rate
for a small local model, priced separately for input and output tokens; OTel GenAI content
capture (the prompt or the completion text) stays off, the same hold recorded in
`_plans/versions.md`, so `detail` is always a short, deterministic summary (`carrier=AIRCO`),
never the model's own prose.

## Append-only, by construction and by guard

`AuditProjectionRoute`'s fold is the only legitimate way `shipping.audit_log_projection` is ever
written: an `INSERT ... ON CONFLICT (id) DO NOTHING`, keyed on the record's own id, so folding a
redelivered or replayed record twice changes nothing. `POST /audit/demo/mutate` is demo code: the
one bypass that writes to the projection directly, `UPDATE ... SET detail = ? WHERE id = ?`,
without appending a correcting record to the log. `audit.mutable` (`AUDIT_MUTABLE` in compose)
gates it: `false`, the default, rejects every call with 403; the endpoint exists at all only to
demonstrate what breaking the discipline looks like.

## The negative witness

Flip `AUDIT_MUTABLE=true` and the demo mutate succeeds: the live projection now carries a detail
the log never recorded. A replay still reads only the untouched log, so it reconstructs the
original detail, and the live projection and the replay no longer match: `POST /audit/replay`
reports `matchesLive: false`. This is the proof the log, not the projection, is the durable
record: `POST /audit/rebuild-live` truncates the live projection and refolds it from the log
alone, erasing the illegal mutation's effect without losing anything the log actually recorded.
`verify.sh` demonstrates the whole cycle live: normal operation (`matchesLive: true`), the
corruption (`matchesLive: false`), and the recovery (`matchesLive: true` again, with
`AUDIT_MUTABLE` restored to `false`).

To confirm that check actually discriminates, `ReplayService.run()` was changed by hand to fold
`REPLAY` by copying rows straight out of `shipping.audit_log_projection` (the live table)
instead of reading `apa.audit.agent-actions` from the beginning. With the log bypassed, a fresh
`./verify.sh` run still showed `matchesLive: true` after the demo mutation, because the "replay"
was now just echoing the same corrupted live row back at itself: the negative-witness check
failed (28/29, check 21), expecting `matchesLive: false` and getting `true`. The hand-edit was
reverted and the suite reverified green (29/29). See `_plans/verification-log.md` for the exact
run.

## Contracts

- `contracts/21-audit-agent-action.schema.json`: the committed JSON Schema for one audit record.
- `contracts/21-replayable-agent-memory-event-sourced-audit.asyncapi.yaml`: AsyncAPI 3.0 for the
  `apa.audit.agent-actions` channel, both operations (`send`, the append; `receive`, the fold).
- `verify.sh` registers the schema in Apicurio (`groups/apa/artifacts/apa.audit.agent-actions`,
  REST API v3) and sets its `COMPATIBILITY` rule to `BACKWARD`, then reads both back to confirm.

## Run

```bash
(cd ../domain-model && mvn -B -q install)
mvn -B verify       # Dev Services Kafka and PostgreSQL; no model, no compose stack needed
./verify.sh
```

`verify.sh` needs Docker Engine with the compose plugin; it builds the service and the shared
domain model inside the image, starts Kafka, PostgreSQL, Apicurio, Keycloak, Ollama and LGTM plus
the service (Keycloak and Ollama are part of the shared `_infra` stack but this chapter uses
neither), registers and checks the Apicurio contract, runs the checks below including the
negative witness and the recovery, and removes everything with `down -v`. Set `KEEP=1` to leave
the stack running for inspection.

## Expected output

```
PASS 1: apa-kafka is healthy
...
PASS 7: apa-replayable-agent-memory-event-sourced-audit is healthy
PASS 8: the schema registers in Apicurio
PASS 9: the COMPATIBILITY rule is set to BACKWARD
PASS 10: the artifact apa.audit.agent-actions exists in group apa
PASS 11: the artifact's compatibility rule reads back as BACKWARD
PASS 12: POST /audit/shipment-advice succeeds for O-21001
PASS 13: the live projection has exactly 2 rows for O-21001
PASS 14: both audit directions (INBOUND and OUTBOUND) appear for O-21001
PASS 15: a replay from the beginning of the log reproduces the live projection
PASS 16: the log is append-only: a mutate attempt is rejected (403) while audit.mutable is false
PASS 17: the rejected mutate attempt left the record unchanged
PASS 18: the app is ready again with audit.mutable=true
PASS 19: the demo mutate now succeeds and overwrites the committed record
PASS 20: the live projection now shows the corrupted detail for O-21001
PASS 21: negative witness: a replay from the untouched log no longer matches the (corrupted) live projection
PASS 22: the app is ready again with audit.mutable=false
PASS 23: a further mutate attempt is rejected again
PASS 24: rebuilding the live projection from the log succeeds
PASS 25: the rebuilt live projection no longer carries the corrupted detail
PASS 26: restored: a replay matches the rebuilt live projection again
PASS 27: Tempo has a trace for service replayable-agent-memory-event-sourced-audit
PASS 28: Prometheus has a metric from replayable-agent-memory-event-sourced-audit
PASS 29: Loki has the fold's own log line (observed, never model prose)
29/29 checks passed
```

## Tests and checks in detail

`AuditTest` (`mvn -B verify`) exercises the whole append-to-fold-to-replay path against Dev
Services Kafka and PostgreSQL: an order named in the committed fixture is recorded with the
fixture's exact carrier and token counts; an order the fixture does not name falls back to
FASTLINE with the fixed fallback token counts; one `shipment-advice` call appends both audit
directions; a replay reproduces the live projection; a mutate attempt is rejected by default.

`verify.sh` adds the live, container-level view: the Apicurio contract registration, the
negative witness with the service actually restarted under `AUDIT_MUTABLE=true`, the recovery by
rebuilding the live projection from the untouched log, and OTel signals reaching Tempo,
Prometheus and Loki.

## Experiments

| Switch | Effect | Failing checks |
|---|---|---|
| `AUDIT_MUTABLE=true` (left set, never restored) | Every demo mutate call succeeds, so a replay never matches live again until `rebuild-live` is called | 16-17 (append-only is no longer enforced) |
| `ReplayService.run()` folding REPLAY from the live table instead of the Kafka log | The negative witness goes blind: a corrupted live row is echoed back as the replay, so `matchesLive` stays `true` | 21 (the negative witness, the bite recorded in `_plans/verification-log.md`) |

## Layout

| Path | Purpose |
|------|---------|
| `src/main/java/.../AuditRecord.java` | one append-only log entry, both directions share the shape |
| `src/main/java/.../AuditAppendRoute.java` | `direct:audit-append` to `kafka:apa.audit.agent-actions`, the only way in |
| `src/main/java/.../AuditProjectionRoute.java` | folds the log into the live projection; the only legitimate write path for it |
| `src/main/java/.../AuditProjectionStore.java` | JDBC for both LIVE and REPLAY tables, plus the demo-only mutate |
| `src/main/java/.../ReplayService.java` | a disposable-group plain Kafka consumer that rebuilds REPLAY, or recovers LIVE, from the log |
| `src/main/java/.../AuditModelDecisionAdvisor.java` | the fixture-and-rule decision source; no live model anywhere in this chapter |
| `src/main/java/.../AuditResource.java` | `/audit/shipment-advice`, `/audit/log/{orderId}`, `/audit/replay`, `/audit/rebuild-live`, `/audit/demo/mutate` |
| `src/main/resources/fixtures/model-decisions.jsonl` | the committed, recorded decisions `AuditModelDecisionAdvisor` replays |
| `src/main/resources/db/migration/` | Flyway migration for `shipping.audit_log_projection` and `shipping.audit_replay` |
| `compose.yaml` | includes the shared infrastructure and adds the service |
| `Containerfile` | UBI 10 multi-stage build; context is `examples/` |
| `verify.sh` | the checks above, including the Apicurio registration |
