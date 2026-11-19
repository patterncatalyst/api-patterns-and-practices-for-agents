# 32 CI for agent-facing APIs

A scripts-only harness with no app of its own, running two gates a CI pipeline for an agent-facing
surface needs:

- **Gate (a), contract drift**: the ch27 harness, reused verbatim. It reuses `07-strict-self-describing-contract`
  for the REST surface and `13-package-api-as-mcp-tools` for the MCP tool surface, neither copied nor
  forked, and checks that each running service still matches its committed contract.
- **Gate (b), schema compatibility**: a JSON Schema registered in Apicurio Registry under the
  `COMPATIBILITY` rule set to `BACKWARD`. A compatible next version is accepted; an incompatible one is
  rejected by the registry itself.

## Gate (a): contract drift

Identical to `examples/27-contract-and-manifest-conformance`'s four checks and its biting negative
witness: Schemathesis (a pinned, one-shot container on the `apa` network, reaching the service by its
compose DNS name) checks the running ch07 service against `contracts/07-order-service.openapi.yaml`;
the served `/q/openapi`, normalized, is diffed against that same committed contract; the ch13 server's
served `tools/list` is diffed against `contracts/13-order-tools.mcp.json`. The same two checks run
again against the committed drifted fixtures `contracts/27-order-service.drift.openapi.yaml` and
`contracts/27-order-tools.drift.mcp.json` (ch27's own fixtures fit unchanged, since this harness reuses
the identical two services) and both fail, proving the checks discriminate.

## Gate (b): schema compatibility, confirmed live

The baseline is `contracts/21-audit-agent-action.schema.json`, registered unchanged in a chapter-specific
Apicurio group (`apa32`), except for one field: `verify.sh` rewrites `$schema` to
`http://json-schema.org/draft-07/schema#` before posting it. That rewrite changes the outcome, not
just the formatting, and was confirmed by posting real candidate schemas against a live Apicurio Registry 3.3.4:

- Registering the baseline **as committed**, with `$schema: https://json-schema.org/draft/2020-12/schema`,
  and then posting any second version at all (compatible or not) gets back **HTTP 422**,
  `UnprocessableSchemaException`, `"SchemaException: #: could not determine version"`. The compatibility
  algorithm never runs; the registry cannot even parse which JSON Schema draft it is looking at.
- Switching only the committed baseline's `$schema` to `http://json-schema.org/draft-07/schema#` (or
  `draft-06`/`draft-04`; `2019-09` still fails the same way as `2020-12`) makes the same compatibility
  checks run and return a real verdict.

With that one field fixed, two confirmed, empirically observed outcomes:

| Candidate change | Confirmed result |
|---|---|
| Add a new **optional**, nullable field (`note`, not in `required`) | **HTTP 200**, new version accepted |
| Add a new **required** field (`correlationId`, added to `required`, no default) | **HTTP 400**, `RuleViolationException`, cause `"A new required property was added at /required"` |

The rejection is **HTTP 400**, not 409: Apicurio Registry 3.3.4 reports a `COMPATIBILITY` rule
violation as a 400 Bad Request carrying `"name":"RuleViolationException"` in the body, which is what
`verify.sh` asserts, rather than treating any non-2xx as proof of a correct rejection (a 422 from the
dialect problem above is also non-2xx, and is not a compatibility verdict at all).

`contracts/32-audit-agent-action.v2-compatible.schema.json` and
`contracts/32-audit-agent-action.v2-breaking.schema.json` are the two frozen candidates, posted in that
order against the registered baseline (compatible first, so the breaking version's one diff, relative
to the compatible version, is exactly the new required field).

## Run

```bash
./verify.sh
```

A fresh stack comes up (both reused ch07/ch13 services, their shared and dedicated Postgres instances,
Apicurio, and the rest of the shared infrastructure), the six gate (a) checks and the six gate (b)
checks run, and the stack is torn down. Set `KEEP=1` to leave it running and probe by hand:

```bash
KEEP=1 ./verify.sh
curl -s http://localhost:8081/apis/registry/v3/groups/apa32/artifacts/apa.audit.agent-action.v2/versions | jq
```

## The sample CI workflow

`ci-agent-facing-api.yml` is a committed sample, displayed by the chapter, not a live pipeline: it runs
no step here, because a GitHub-hosted runner has no Docker stack already up. It calls the identical
`./verify.sh` a pull request would need to pass, against a stack the job itself would bring up on its
own runner.

## Layout

| Path | Purpose |
|------|---------|
| `compose.yaml` | includes the shared infrastructure and builds ch07 and ch13 as two services on the `apa` network, ch07 on its own Postgres |
| `verify.sh` | gate (a) and gate (b), both with a negative witness |
| `ci-agent-facing-api.yml` | the sample CI workflow the chapter displays |
| `../../contracts/07-order-service.openapi.yaml`, `../../contracts/13-order-tools.mcp.json` | gate (a)'s committed contracts |
| `../../contracts/27-order-service.drift.openapi.yaml`, `../../contracts/27-order-tools.drift.mcp.json` | gate (a)'s drifted fixtures, reused unchanged from ch27 |
| `../../contracts/21-audit-agent-action.schema.json` | gate (b)'s baseline schema, reused unchanged on disk |
| `../../contracts/32-audit-agent-action.v2-compatible.schema.json`, `../../contracts/32-audit-agent-action.v2-breaking.schema.json` | gate (b)'s two frozen candidate versions |
