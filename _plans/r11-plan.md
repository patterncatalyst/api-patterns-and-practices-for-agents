---
layout: plan
title: "r11 plan (Part 7: Run in production, chapters 33-36)"
render_with_liquid: false
output: false
---

# r11 Implementation Plan: Part 7 "Run in production" (chapters 33-36)

Status: DRAFTED, awaiting owner review before build. Branch `release/r11` off `main` (r10 shipped at 2863acb; cleanup 28c2056). Four pattern chapters, four examples. ch37 (Kubernetes/Strimzi) and ch38 (final project) are r12 and OUT of scope. All house gates apply.

## Approach

Four pattern chapters, each with the full spine + exactly one AI callout, each runnable from a clean one-stack-at-a-time Docker compose with a biting negative witness. Shape decisions:

- **ch33 (outward audit)** = a NEW small direct-Ollama langchain4j consumer (ch24 shape, direct model call, no remote tool). The point is the model call's own GenAI telemetry. CONFIRMED: `quarkus-langchain4j` 1.13.4 + `quarkus-opentelemetry` auto-emit OTel GenAI signals out of the box (no hand-rolled span); the example adds only a durable token/cost recorder so the bite is a robust durable-row witness, not a flaky telemetry-absence check.
- **ch34 (inward audit)** = a NEW consumer that makes OUTBOUND calls to another API (reuse ch13's MCP image via build-context, ch24 precedent) and records each outbound call to an append-only audit trail (reuse ch21 mechanics, other direction). Audited unit = one outbound tool/HTTP call.
- **ch35 (identity guardrails)** = a NEW app extending ch16's gating (Keycloak `apa` realm, bearer `/mcp`, `@RolesAllowed`, by-hand scope check, colon-split gotcha) with RFC 8693 Token Exchange and step-up ACR. Standard Token Exchange v2 CONFIRMED supported and server-default-on in Keycloak 26.2+ (so 26.8.2), but needs a per-client realm attribute, so the shared realm JSON changes.
- **ch36 (traffic/resource guardrails)** = ONE app leading with two fully-bitten guardrails (SmallRye `@RateLimit` -> 429, and Apicurio schema-validity ingest gate), with Kafka topic ACLs and quotas written as documented variants with exact config. Live ACL enforcement needs a different Kafka (SASL + StandardAuthorizer), a separable build that belongs with ch37/Strimzi (`KafkaUser`); cramming four bites + a SASL broker into one example breaks the "reader in one sitting" rule. Owner question 1.

Rejected alternatives (one line each):
- ch33 reuse ch24's remote-MCP consumer unchanged: rejected, ch24 asserts on a remote tool's seeded value; ch33 needs the model call itself as the audited unit, so a direct Ollama `@RegisterAiService` is smaller and on-point.
- ch33 bite on absence of the gen_ai span/metric: rejected per the ch17 lesson (Prometheus keeps the last sample live across a restart, so absence checks pass vacuously); the durable cost row is the bite.
- ch34 relying on a langchain4j `ToolExecutionListener`/`ChatModelListener`: rejected as non-deterministic to assert and hard to disable via env; wrap the outbound call in one app bean (`AuditedToolCaller`) toggled by a config flag instead.
- ch35 one combined "exchange == step-up" token: kept as the mechanism but presented as two named guards (scope gate; acr/step-up gate obtained via exchange) so both owner-listed concepts are visible.
- ch36 one example demonstrating all four guardrails live: rejected (sprawl + a second SASL Kafka); lead with two, document two.
- ch36 enforcing Kafka ACLs on the shared `_infra` Kafka: impossible without editing shared infra (PLAINTEXT, no authorizer, every principal `User:ANONYMOUS`); would need a dedicated broker, see owner question 1.

## Per-chapter design

### ch33 Observability for Agent Traffic (`examples/33-observability-for-agent-traffic`)
- Service set / reuse: shared `_infra` + `compose.lgtm.yaml` + one NEW `app` (direct-Ollama `@RegisterAiService` with one `@Tool`). Ollama + Postgres (durable cost row) + LGTM. No remote MCP server.
- mvn lane: YES. MODEL=off fixture lane: `%test` forces `agent.model-source=fixture`; fixture supplies recorded token counts; test asserts the cost recorder writes the durable row, plus a unit recorder-disabled witness. `%test.quarkus.otel.sdk.disabled=true`.
- What the stack emits (CONFIRMED, no custom span): spans in Tempo `langchain4j.aiservices.<Interface>.<method>` + child `langchain4j.tools.<tool>` with `gen_ai.*` attributes (`gen_ai.operation.name`, `gen_ai.system`, `gen_ai.request.model`, `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`); metrics `langchain4j.aiservices.timed`/`.counted` and token-usage `gen_ai.client.token.usage` (Prometheus `gen_ai_client_token_usage_total`, tag `gen_ai_token_type`=input/output). Content capture OFF by default (matches versions.md "OTel GenAI semantic conventions", Development status). Cost derived downstream from token counts.
- Observable effect verify.sh asserts (live, MODEL=on): (1) a `langchain4j.aiservices.*` trace in Tempo; (2) `gen_ai_client_token_usage_total` present + nonzero in Prometheus; (3) a durable `token_cost` row (call id, model, input/output tokens, derived cost); (4) the tool's log line in Loki.
- Biting negative witness (env, no source edit): `COST_RECORDER_ENABLED=false` recreates `app`; a fresh call still answers and still emits gen_ai telemetry, but no `token_cost` row -> the row-absence check bites.
- Flyway isolation: own schema (e.g. `agenttraffic`) on shared Postgres; no separate DATABASE.
- New contracts: none required (optional `contracts/33-agent-traffic-telemetry.md`).
- New pin: NONE. Flag: confirm `gen_ai_client_token_usage_total` emits live on 1.13.4 (dev-branch doc).

### ch34 Auditing AI-Using Code (`examples/34-auditing-ai-using-code`)
- Service set / reuse: shared infra + LGTM + ch13's `package-api-as-mcp-tools` via build-context (reused unchanged) + one NEW `app` (our langchain4j code calling ch13's remote `orderStatus`). Every outbound call routes through one `AuditedToolCaller` bean that appends an audit record (reuse ch21 `AuditAppendRoute` -> `apa.audit.agent-actions` + id-keyed projection), then performs the call.
- mvn lane: YES. MODEL=off fixture lane: `FixtureConsumer` calls the remote tool via a hand-built `McpClient` (or in-process MCP server, ch24's `FixtureOrderStatusMcpServer`), through the SAME `AuditedToolCaller`.
- Audited unit + durable schema: one outbound call = {audit id, correlation/order id, direction=OUTBOUND, target (tool/HTTP), argument digest, occurred_at, optional token/cost}. Append-only Kafka log + folded projection table (ch21 shape), optionally the committed JSON schema registered in Apicurio with `COMPATIBILITY=BACKWARD`.
- Observable effect verify.sh asserts (live, MODEL=on): one `POST /consume` for the seeded order produces an append-only audit row for the outbound `orderStatus` call carrying ch13's unguessable seeded value (ch24's FL-778821 witness proves the call went out); a replay reproduces the projection; the append-only guard rejects a mutate by default; Tempo/Prometheus/Loki signals.
- Biting negative witness (env): `AUDIT_INTERCEPTOR_ENABLED=false`; the outbound call still succeeds (business row still written) but NO audit row -> the audit-row-absence check bites.
- Flyway isolation: app owns `audit`/`consumer` schema, ch13 owns `orders`/`payments`/`shipping`; different tables -> shared Postgres, NO separate database.
- New contracts: `contracts/34-outbound-call-audit.schema.json` (draft-07, per the Apicurio-3.3.4 draft limitation).
- New pin: NONE.

### ch35 Identity and Authorization Guardrails (`examples/35-identity-and-authorization-guardrails`)
- Service set / reuse: shared infra (Keycloak + Postgres) + LGTM + one NEW app extending ch16's gated MCP surface (bearer `/mcp`, `@RolesAllowed("agent")`, by-hand `scope` check via `JsonWebToken`). New acr/step-up + exchange handling, so a new app, not a reuse of ch16's image.
- mvn lane: YES. Tests use `@TestSecurity`/`@OidcSecurity` for role + scope + acr without a live IdP (`%test.quarkus.oidc.tenant-enabled=false`). Live verify.sh fetches real tokens and performs a real token exchange against `apa`.
- Added beyond ch16 (both CONFIRMED): Token Exchange (RFC 8693) Standard v2 supported, feature `token-exchange-standard:v2` ON by default in 26.8.2; per-client attribute `standard.token.exchange.enabled=true`; endpoint = token endpoint, `grant_type=urn:ietf:params:oauth:grant-type:token-exchange`, `subject_token`, `subject_token_type=...:access_token`, `requested_token_type`, `audience`; requester must be confidential, same realm, in the subject token's `aud` (waived when the token was issued to the requester itself). v2 only (legacy v1 is deprecated preview). Step-up via ACR: a low-assurance token (read scope, low/absent `acr`) can read; the destructive tool requires a higher `acr`, obtained via the exchange step; acr floor enforced by-hand against the `acr` claim (ch16 by-hand precedent), to avoid over-claiming a native step-up feature on 3.40.1.2 bearer mode (owner question 3).
- Observable effect verify.sh asserts (live): unauthenticated `/mcp` -> 401; `tools/list` matches committed contract; a read-scoped/low-acr token calls the read tool but is rejected by the destructive tool (order unchanged); a real RFC 8693 exchange yields a write-scoped/high-acr token; the exchanged token clears both gates and the destructive op proceeds.
- Bites (env where possible): (a) wrong/missing scope -> rejected, order unchanged; (b) `STEPUP_ENFORCED=false` recreates `app` with the acr floor removed, letting the low-acr token reach the destructive op -> the "stays unchanged after under-assured attempt" check bites.
- Realm/stack config to add (owner question 2, additive + backward-compatible to `examples/_infra/keycloak/realm-apa.json`): (1) requester confidential client with `standard.token.exchange.enabled=true`; (2) likely a SECOND client (requester + target audience, e.g. `apa-agent-bff` -> `apa-mcp`) with an audience mapper so the requester is in `aud`; (3) an `acr` mapper / client scope to stamp the step-up assurance onto the exchanged token. Alternative: a ch35-local realm variant.
- Flyway isolation: owns `orders` schema, runs alone, no separate database.
- New contracts: `contracts/35-order-tools.mcp.json` + `contracts/35-token-exchange-flow.md`.
- New pin: NONE (`quarkus-oidc` platform-managed). Realm-config change flagged.

### ch36 Traffic and Resource Guardrails (`examples/36-traffic-and-resource-guardrails`)
- Chapter scope (RECOMMENDED; owner question 1): lead with two demonstrated+bitten guardrails in ONE app at an agent-facing ingest endpoint; document the other two with exact config.
  1. Rate limit (CONFIRMED): `@RateLimit(value=N, window=W, windowUnit=...)` from `io.smallrye.faulttolerance.api` (`quarkus-smallrye-fault-tolerance`). Over-limit throws `RateLimitException`, default HTTP 500, so map to 429 with a `@ServerExceptionMapper` (+ `application/problem+json`, ch10 shape). Metrics auto-emit to Micrometer/OTel.
  2. Apicurio schema-validity ingest gate: validate the inbound payload against a registered schema before accepting; invalid payload rejected. Use JSON Schema draft-07 (2020-12/2019-09 un-checkable on 3.3.4, HTTP 422); a real rule rejection is HTTP 400 `RuleViolationException`.
  - Documented variants (config shown + cited, not run live here): Kafka topic ACLs via KRaft `StandardAuthorizer` (`authorizer.class.name`, `super.users`, `allow.everyone.if.no.acl.found=false`, SASL principals, `kafka-acls.sh`); client quotas via `kafka-configs.sh` (`producer_byte_rate`/`consumer_byte_rate`/request quotas). These need SASL + an authorizer-enabled broker (shared `_infra` Kafka is PLAINTEXT); live enforcement is the natural home of ch37/Strimzi.
- Service set / reuse: shared infra (Apicurio + Kafka + Postgres) + LGTM + one NEW app. No model, no Ollama use, no MODEL=off lane (the one AI callout is prose only). If owner chooses live ACLs (Q1), add a dedicated `kafka-acl` broker (`apache/kafka:4.3.2`, SASL_PLAINTEXT + StandardAuthorizer + own config) on the example's own compose (the `postgres-contract` dedicated-instance precedent).
- mvn lane: YES. In-JVM: fire N+1 calls, assert the (N+1)th maps to 429; feed valid + invalid payloads, assert accept/reject.
- Observable effect verify.sh asserts (live): over-limit -> 429 + problem+json (durable rejected-request counter); invalid payload rejected by the schema gate (Apicurio rule read back); rate-limit + rejection metrics in Prometheus; LGTM signals.
- Bites (env, one per demonstrated guard): (a) `RATE_LIMIT_ENABLED` / generous-window override -> the (N+1)th call now 2xx -> "over-limit is 429" bites; (b) `SCHEMA_GATE_ENABLED=false` -> invalid payload accepted -> "invalid payload rejected" bites. (If live ACLs: an unauthorized principal's produce returns `TopicAuthorizationException`.)
- Flyway isolation: own schema, runs alone, no separate database.
- New contracts: `contracts/36-ingest.schema.json` (draft-07) + a 429 OpenAPI fragment.
- New pin (FLAG): `quarkus-smallrye-fault-tolerance` platform-managed by quarkus-bom 3.40.1.2 (no version pin) but NEW to the book: record a versions.md row. If live ACLs: record the SASL_PLAINTEXT + StandardAuthorizer config surface (no new image).

## Waves

Executors Sonnet, one chapter+example each; disjoint example/contract/`_docs` files let code builds run in parallel, but shared files `bibliography.md`, `_plans/claims.md`, `_plans/verification-log.md`, `_plans/versions.md`, and `examples/_infra/keycloak/realm-apa.json` force serialization (no worktrees). Checkpoint-commit each chapter.

- G0 (survey + decisions, blocking): record in versions.md `quarkus-smallrye-fault-tolerance` (ch36); confirm `gen_ai_client_token_usage_total` on 1.13.4 (ch33); Keycloak 26.8.2 token-exchange-standard:v2 default-on + per-client attribute (ch35); StandardAuthorizer/quota config if Q1 = live ACLs (ch36). Finalize the realm-apa.json diff. Resolve owner questions 1-5.
- W1 ch33, W2 ch34, W3 ch35, W4 ch36 (code builds, parallel-capable): per chapter: example (src, Containerfile, compose.yaml, application.properties, verify.sh fresh-run contract), mvn tests, `contracts/NN-*`, diagram spec + generated svg/excalidraw, chapter `_docs/NN-*.md` (full spine + one AI callout, no em/en dashes, stands alone). ch35 also touches shared realm-apa.json (serialize; keep additive so all examples still boot).
- W5 (static-gate, serialized shared-file writes): each chapter appends claims.md + bibliography.md rows; then validate.py --self-test, validate.py, voice-scan.sh, idiomatic-scan.sh, host_ids self-test + check, site-build.sh.
- W6 (live lane, one stack at a time, orchestrator): run each verify.sh fresh, run each env bite + confirm it falls, restore + re-verify green; append verification-log rows; shellcheck clean per verify.sh.
- W7 (validate + ship): Opus validation; CI green via `gh run view --json conclusion`==success; voice-scan no args; auto-merge on Opus-clean + CI-green; tag `r11.x`; notes-only release; delete branch.

## Acceptance criteria

- ch33: live verify.sh asserts a `langchain4j.aiservices.*` trace in Tempo, `gen_ai_client_token_usage_total` present+nonzero in Prometheus, a durable `token_cost` row, the tool log line in Loki; mvn (MODEL=off) proves the cost recorder + a unit recorder-off witness; the env bite removes only the durable row (telemetry still flows), check falls, restore green. No new pin.
- ch34: live verify.sh shows an append-only audit row for the outbound `orderStatus` call carrying ch13's seeded value, a replay reproducing the projection, the append-only guard rejecting a mutate by default; mvn (MODEL=off) via direct `McpClient`/in-process server through the same `AuditedToolCaller`; the `AUDIT_INTERCEPTOR_ENABLED=false` bite leaves the outbound effect but drops the audit row, check falls, restore green. Shared Postgres, distinct schema.
- ch35: live verify.sh fetches real tokens, performs a real RFC 8693 v2 exchange, shows the read/low-acr token rejected by the destructive tool and the exchanged/high-acr token accepted; mvn via `@TestSecurity` for role/scope/acr; bites: missing scope rejected, and `STEPUP_ENFORCED=false` lets the under-assured call through (check falls), restore green. Realm additions additive; every other example still boots.
- ch36: live verify.sh shows over-limit -> 429 problem+json and an invalid payload rejected by the Apicurio gate (rule read back); mvn asserts the (N+1)th call maps to 429 and accept/reject of valid/invalid payloads; two env bites each fall and restore; `quarkus-smallrye-fault-tolerance` recorded in versions.md; ACLs + quotas documented with cited config (live ACLs only if Q1 says so).
- Iteration: all four examples pass fresh with biting witnesses logged in verification-log.md; all static gates clean; claims/bibliography/versions updated; CI green; one PR, squash-merge, tag `r11.x`. ch37/ch38 untouched.

## Risks (+ detection)
- ch33 gen_ai metric not emitting on 1.13.4 (dev-branch doc): Prometheus assertion fails. Mitigation: durable cost row is the robust witness; fall back to `langchain4j.aiservices.counted` + span `gen_ai.usage.*` attributes, record real names. Detected W6.
- ch34 outbound interception doesn't fire for MCP-client calls: happy-path audit row missing. Mitigation: intercept in app code (`AuditedToolCaller`), not a framework listener; mvn catches before live.
- ch35 token exchange 400 (per-client attribute missing, requester not in aud, or not confidential): exchange call fails. Mitigation: G0 nails the realm diff; exchange of a client's own token waives the aud requirement as fallback; acr unstampable under client_credentials -> stamp via mapper/client scope, else model step-up as scope elevation. Realm additive so others unaffected. Detected W6.
- ch36 @RateLimit surfaces as 500 not 429: mvn catches missing mapper. Apicurio draft-2020-12 un-checkable on 3.3.4: use draft-07. Live ACLs need SASL (shared Kafka PLAINTEXT): hence variant/defer; if attempted, a dedicated broker SASL/authorizer misconfig shows as every produce denied at W6.
- Negative-witness vacuity (ch17 lesson): no Prometheus/Tempo absence checks for any bite; all four bites assert a durable-row absence or an HTTP status.
- Resource budget: Ollama + LGTM + app per stack; MODEL=off keeps mvn/CI off Ollama; one stack at a time enforced by verify.sh fresh-run.

## New pins / contracts (each flagged for owner confirmation)
- ch36: `quarkus-smallrye-fault-tolerance` (platform-managed by quarkus-bom 3.40.1.2, no version pin) -> versions.md row. FLAG.
- ch36 (only if Q1 = live ACLs): dedicated `apache/kafka:4.3.2` broker with SASL_PLAINTEXT + StandardAuthorizer config (no new image; new config surface) -> record. FLAG.
- ch35: `examples/_infra/keycloak/realm-apa.json` additive changes (token-exchange client attribute, requester/target client pair + audience mapper, acr mapper/scope). Not a pin; shared-infra config change. FLAG.
- ch33: no new pin; confirm `gen_ai_client_token_usage_total` emits live on 1.13.4. FLAG.
- Contracts (new): `contracts/34-outbound-call-audit.schema.json` (draft-07), `contracts/35-order-tools.mcp.json` + `35-token-exchange-flow.md`, `contracts/36-ingest.schema.json` (draft-07) + 429 OpenAPI fragment, optional `contracts/33-agent-traffic-telemetry.md`. No other new pins.

## Open questions for the owner
1. ch36 scope: OK to lead with rate-limit (`@RateLimit`->429) + Apicurio schema-validity gate (both fully bitten, runnable) and document Kafka topic ACLs + quotas as variants (deferring LIVE ACL enforcement to ch37/Strimzi `KafkaUser`)? Or add a dedicated SASL + StandardAuthorizer Kafka to ch36 to demonstrate ACLs live (extra broker, +1 bite, heavier read)?
2. ch35 realm: approve additive edits to the shared `realm-apa.json` (token-exchange-enabled confidential client + audience mapper so the requester is in `aud` + an acr mapper/client-scope)? Or ship a ch35-local realm variant to keep the shared realm untouched?
3. ch35 step-up enforcement: acceptable to enforce the acr floor by-hand against the token's `acr` claim (ch16 by-hand precedent) rather than a Quarkus-OIDC native step-up mechanism on 3.40.1.2 bearer mode?
4. ch36 dependency: confirm `quarkus-smallrye-fault-tolerance` as a new (platform-managed) book dependency.
5. ch33 bite: confirm the biting witness is the durable cost-row (deterministic), with the gen_ai span + token metric asserted as positive observable effects (not as the bite), per the ch17 vacuous-absence lesson.

## Critical files to reuse
- examples/16-securing-gating-mcp-tools/{verify.sh,src/main/resources/application.properties} (ch35 OIDC base)
- examples/24-langchain4j-programmatic-consumer/{src/main/resources/application.properties,verify.sh} (ch33/ch34 direct-Ollama + MODEL=off + ch13 build-context + durable recorder)
- examples/21-replayable-agent-memory-event-sourced-audit/src/main/java/.../{AuditAppendRoute.java,AuditResource.java} (ch34 append-only audit + default-off mutate bypass)
- examples/27-contract-and-manifest-conformance/compose.yaml (scripts-only reuse + Flyway isolation precedent for ch36's dedicated-broker option)
- examples/_infra/keycloak/realm-apa.json + examples/_infra/compose.yaml (ch35 realm additions; shared infra ch36 cannot modify for ACLs)
- CLAUDE.md, _plans/versions.md, _plans/claims.md, _plans/verification-log.md (house rules, pins, row formats)
