---
layout: plan
title: "r10 plan (Part 6: Ship)"
render_with_liquid: false
output: false
---

# r10 Implementation Plan: Part 6 "Ship" (chapters 31-32)

Status: APPROVED PENDING (drafted pre-compaction; owner review before build). Branch `release/r10` off `main` (r09 shipped at d4685a2). Two pattern chapters, two examples. No new version pins. All house gates apply.

## Approach

- ch31 ships ONE versioned release of a tool surface as three artifacts sharing a single source-of-truth version: an MCP image (Streamable HTTP), the same build's fast-jar driven over stdio (stdin/stdout, no network), and a served `llms.txt`.
- ch32 ships a two-gate CI for agent-facing APIs: a contract-drift gate (reuse ch27's Schemathesis + served-vs-committed OpenAPI + tools/list-vs-manifest logic) and an Apicurio BACKWARD schema-compatibility gate (register baseline under COMPATIBILITY=BACKWARD, then POST a compatible new version accepted + a breaking new version the registry rejects with 409).
- Both keep the full pattern spine and a biting negative witness wired into the normal run (ch27 precedent: no source edit needed to fire).
- **Single source of truth for ch31 version:** Maven `${project.version}` -> compiled into jar as `quarkus.application.version` -> reported as MCP `initialize` `result.serverInfo.version` over BOTH transports -> injected into served `/llms.txt` version line -> read once by the release script to tag the OCI image. HTTP image and stdio jar are the SAME artifact in one image, so version + tool surface agree by construction; the bite is a deliberate per-invocation override.
- **ch31 app is DB-free:** 2-3 `@Tool`s return fixed/seeded data (reuse `examples/domain-model` records + in-memory map) so initialize+tools/list+tools/call work over pure stdin/stdout, no datasource, no network.
- **ch32 reuses ch07 + ch13 images unchanged** and applies ch27's Flyway isolation (dedicated `postgres-contract` for ch07, shared `postgres` for ch13, since both own `orders.orders`).
- Alternatives rejected: reuse ch13 image for ch31 (no llms.txt; can't thread one version through 3 artifacts); push to external/OCI registry (violates no-vendor-cloud / no-external-push; demonstrate locally with `docker build -t name:$VERSION`, no push); wire ch32 workflow into `.github/workflows/` (GH runners have no compose stack; ship it as a committed illustrative artifact in the example dir + verify.sh runs the identical gate logic locally); fold ch31 llms.txt into ch08's image (wrong artifact: ch08's describes the REST service, no version, no MCP tool surface).

## Per-chapter design

### ch31 Publish the Tool Surface (`examples/31-publish-the-tool-surface`) - PATTERN CHAPTER, mvn lane YES
- Dedicated small Quarkus app: `quarkus-mcp-server-http` + `-stdio` (via `quarkus-mcp-server-bom:2.0.4`, pinned) + `quarkus-rest` for `/llms.txt`. DB-free tool surface (2-3 `@Tool`s over domain-model records). Own Containerfile (ch13 convention, context `examples/`, UBI10 openjdk-25:1.24-20 build / -runtime:1.24-20 runtime, fast-jar layers chown 185). compose.yaml includes ../_infra + compose.lgtm.yaml, publishes HTTP on a non-infra host port, `%prod quarkus.mcp.server.stdio.enabled=false` (detached container has no stdin). Config: serverInfo version from `${quarkus.application.version}` (confirm key `quarkus.mcp.server.server-info.version` against the 2.0.4 jar; fall back to handshake default serverInfo.version which is already the app version); llms.txt reads the same version from config.
- Build/ship: `docker build -f 31-.../Containerfile -t publish-the-tool-surface:$VERSION examples` where $VERSION = pom version read once; same image contains /deployments/quarkus-run.jar (the stdio jar).
- verify.sh (fresh-run) asserts: (1) image runs, HTTP initialize+tools/list reports serverInfo.version==$VERSION and tool set matches committed `contracts/31-order-tools.mcp.json` (reuse ch27/ch13 mcp helpers); (2) stdio jar answers initialize+notifications/initialized+tools/list by piping JSON-RPC to `docker run -i --rm` of the SAME image with `QUARKUS_MCP_SERVER_STDIO_ENABLED=true`, no network, reads stdout; reports serverInfo.version==$VERSION and the SAME tool set; (3) served `/llms.txt` is text/plain, lists exactly that tool surface, carries $VERSION, matches committed `contracts/31-llms.txt`; (4) all three version strings equal.
- Observable effect: one version string reported identically by HTTP handshake, stdio handshake, and served llms.txt, with image/jar/llms.txt naming the same tool surface.
- BITING NEGATIVE WITNESS (in normal run, no source edit): re-run the stdio one-shot once with `QUARKUS_MCP_SERVER_SERVER_INFO_VERSION` overridden to a mismatch (e.g. 9.9.9); the "all three agree on one version" check must FAIL for that invocation. Document the reproducible source-edit bite in README (hardcode llms.txt version literal -> agreement check falls).
- mvn lane: in-JVM test (ch12/ch13 style) asserts serverInfo.version == Maven project version, tools/list == committed manifest, /llms.txt lists tools + carries version. `%test.quarkus.otel.sdk.disabled=true`.
- Contracts committed: `contracts/31-order-tools.mcp.json`, `contracts/31-llms.txt` (optional `contracts/31-release-manifest.json`).

### ch32 CI for Agent-Facing APIs (`examples/32-ci-for-agent-facing-apis`) - PATTERN CHAPTER, scripts-only (no mvn)
- No app. compose.yaml includes ../_infra + LGTM, reuses `07-strict-self-describing-contract` (built from its Containerfile, context examples/) with a dedicated `postgres-contract` (ch27 fix), and `13-package-api-as-mcp-tools` on shared `postgres`; Apicurio from _infra. Host ports mirror ch27 (8080 ch07, 8088 ch13).
- Gate (a) contract-drift: reuse ch27 logic verbatim (Schemathesis 4.33.3 pinned, by service DNS on apa net, against committed `contracts/07-order-service.openapi.yaml`; served /q/openapi normalized == committed; served ch13 tools/list == committed `contracts/13-order-tools.mcp.json`).
- Gate (b) Apicurio BACKWARD: reuse ch21/ch19 mechanics. Register baseline JSON schema (reuse `contracts/21-audit-agent-action.schema.json` as baseline in a ch32 group) via POST /apis/registry/v3/groups/{g}/artifacts, set COMPATIBILITY=BACKWARD via POST .../rules, read back BACKWARD. Then POST .../artifacts/{id}/versions twice: backward-COMPATIBLE new version (add optional property) accepted (2xx); backward-INCOMPATIBLE new version rejected (409 RuleViolationException). CONFIRM the exact rejected JSON-schema change live (JSON-schema compat semantics differ from Avro).
- Observable effect: a breaking OpenAPI/manifest drift and a BACKWARD-incompatible schema change are both blocked; conformant contract + compatible schema change pass.
- BITING NEGATIVE WITNESSES (in normal run): (1) same Schemathesis run vs committed drifted OpenAPI + same tools/list diff vs committed drifted manifest both FAIL (reuse contracts/27-*.drift.* or ship contracts/32-*.drift.*); (2) breaking schema new-version POST rejected by Apicurio BACKWARD (409) while compatible accepted - the registry IS the gate.
- "CI" deliverable: ship a committed sample workflow in the example dir (e.g. `examples/32-ci-for-agent-facing-apis/ci-agent-facing-api.yml`) the chapter displays, plus verify.sh running the identical gate logic locally. Do NOT place in `.github/workflows/`. No forwarding constructs (keep validate.py/voice-scan clean).
- Pattern subheading: no Camel route -> use `### How Quarkus models it` or a gate-modeling heading in validate.py's accepted set (confirm against check_chapter_spine).
- Contracts committed: `contracts/32-*.v2-compatible.schema.json`, `contracts/32-*.v2-breaking.schema.json`; drift fixtures (reuse ch27's or add contracts/32-*.drift.*); the sample workflow file. Baseline can reuse contracts/21-audit-agent-action.schema.json.

## Waves
0. **G0 survey (orch):** confirm NO new deps (ch31: mcp-server-bom 2.0.4, quarkus-rest, UBI10 - all pinned; ch32: Schemathesis 4.33.3, Apicurio 3.3.4, curl/jq/python - pinned/host). No versions.md rows; record "r10 introduces no new dependency; no re-survey." Confirm `quarkus.mcp.server.server-info.version` key + serverInfo-version default vs the 2.0.4 jar. Confirm the exact JSON-schema change Apicurio BACKWARD rejects.
1. **Wave A ch31 (Sonnet):** new module examples/31-publish-the-tool-surface (pom, DB-free @Tool bean(s), version-aware LlmsTxtResource (ch08 shape), application.properties, in-JVM test, Containerfile, compose.yaml, verify.sh, README), _docs/31-publish-the-tool-surface.md, figure spec, contracts/31-order-tools.mcp.json + 31-llms.txt, bibliography + claims.
2. **Wave B ch32 (Sonnet):** new dir examples/32-ci-for-agent-facing-apis (compose.yaml with ch07+postgres-contract+ch13+infra, verify.sh with both gates + both witnesses, sample ci-agent-facing-api.yml, README), _docs/32-ci-for-agent-facing-apis.md, figure spec, contracts/32-* (compatible/breaking schema + drift fixtures), bibliography + claims.
3. **Wave C static gates (Sonnet, before live):** validate.py --self-test + validate.py; voice-scan.sh (NO args, covers READMEs+chapters); idiomatic-scan.sh; host_ids self-test + check; site-build.sh; `mvn -B verify` ch31 (after domain-model install); shellcheck both verify.sh.
4. **Wave D live lane (ORCH):** run ./verify.sh ch31 then ch32, fresh, one at a time. Confirm pass counts + every witness bites + restores green; record in verification-log + flip claims Verified. WATCH: ch31 stdio-over-stdin (JSON-RPC on stdout, logs on stderr - parse JSON lines with jq; maybe force quarkus.log.console.stderr=true); ch32 Apicurio BACKWARD must actually 409.
5. **Wave E ship:** Opus validate -> squash-merge on green (verify `gh run view --json conclusion`==success SEPARATELY) -> tag r10.0, notes-only release, delete branch.

## Acceptance criteria
- ch31: mvn -B verify green (serverInfo.version==project version, tools/list==committed, llms.txt lists tools+version); live verify.sh all-pass fresh; HTTP + stdio + llms.txt report ONE identical version + SAME tool surface; committed 31-order-tools.mcp.json + 31-llms.txt match; version-mismatch bite fails the agreement check then restores; shellcheck clean.
- ch32: live verify.sh all-pass; conformant OpenAPI + compatible schema pass; drifted OpenAPI + drifted manifest + breaking schema each rejected (breaking POST 409 from Apicurio BACKWARD); Flyway isolation holds (ch07 postgres-contract, ch13 shared); sample workflow committed + clean; shellcheck clean.
- Both: full spine passes check_chapter_spine; exactly one callout each (4 labelled + 1 Check); >=1 spec figure each; no em/en dashes; standalone; voice/idiomatic/host_ids 0; site builds; CI ci-ok green; verification-log + claims updated; versions.md unchanged (recorded no new deps).

## Risks (+ detection)
- ch31 stdio-over-stdin: logs could pollute stdout -> corrupt JSON-RPC. Mitigate: parse only JSON lines with jq / force logs to stderr. Detect: stdio tools/list parse fails live.
- ch31 serverInfo.version source: if `quarkus.mcp.server.server-info.version` not a real 2.0.4 key, handshake defaults to quarkus.application.version - use that. Detect: mvn-lane serverInfo assertion fails.
- ch31 agreement bite vacuous: if any version is hardcoded not derived, mismatch can't fire. Mitigate: thread all 3 from ${project.version}; bite is per-invocation env override. Detect: agreement check passes under override.
- ch32 Apicurio BACKWARD rejection: chosen "breaking" JSON-schema change might be accepted (JSON-schema compat != Avro). Mitigate: confirm the exact rejected change live before freezing the fixture; keep a clearly-compatible counterpart. Detect: breaking POST returns 2xx not 409.
- ch32 Flyway collision: reuse ch07+ch13 on one Postgres fails. Mitigate: ch27's postgres-contract. Detect: app Flyway exits at startup, up --wait never healthy.
- Sample workflow tripping gates: example-dir .yml scanned by validate.py (no-forwarding) + voice-scan. Mitigate: docker compose + verify.sh only, no forwarding, clean prose. Detect: validate/voice fail in Wave C.
- One-stack-at-a-time: run ch31 and ch32 strictly one at a time (full down -v). Detect: compose port-bind errors.

## New pins / contracts
- NEW PINS: NONE. ch31 reuses quarkus-mcp-server-bom:2.0.4, quarkus-rest (quarkus-bom 3.40.1.2), UBI10 openjdk-25(-runtime):1.24-20; ch32 reuses schemathesis 4.33.3 (r09), apicurio 3.3.4 (r02), curl/jq/python (host). versions.md gains no rows; record "r10 introduces no new dependency; no re-survey."
- NEW CONTRACTS: contracts/31-order-tools.mcp.json, contracts/31-llms.txt (optional 31-release-manifest.json); contracts/32-*.v2-compatible.schema.json, contracts/32-*.v2-breaking.schema.json, ch32 drift fixtures (reuse 27-*.drift.* or add 32-*.drift.*); ch32 baseline can reuse contracts/21-audit-agent-action.schema.json.

## Critical files to reuse
- examples/27-contract-and-manifest-conformance/verify.sh (ch32 drift-gate + MCP handshake helpers)
- examples/21-replayable-agent-memory-event-sourced-audit/verify.sh (Apicurio BACKWARD register/rule/read-back helpers)
- examples/13-package-api-as-mcp-tools/{Containerfile,application.properties} (ch31 image/stdio/serverInfo conventions)
- examples/08-semantic-metadata-llms-txt/.../LlmsTxtResource.java (ch31 version-aware llms.txt shape)
- CLAUDE.md + _plans/versions.md (house rules, pins, conventions)
