# 27 Contract and manifest conformance

A scripts-only harness with no app of its own. It checks that a running service still matches its
published contract and its published tool manifest, not merely that it starts and answers a health
probe. It reuses two already-shipped example images unchanged, each built from its own Containerfile
and build context (`examples/`, so the shared domain model installs first), the same reuse pattern
22-consuming-an-mcp-server-from-claude-code and 24-langchain4j-programmatic-consumer use:

- `07-strict-self-describing-contract` provides the OpenAPI 3.1 REST surface. The committed contract
  is `contracts/07-order-service.openapi.yaml`.
- `13-package-api-as-mcp-tools` provides the MCP tool manifest. The committed manifest is
  `contracts/13-order-tools.mcp.json`.

Neither service is copied or forked. The harness talks to them over the network and compares what
they actually serve against what the repository commits.

## What conformance means here

A service that boots and reports ready can still have drifted away from its contract: an operation
renamed, a field tightened, an error response dropped, a tool quietly removed. Readiness probes do
not catch that. Four checks do.

### OpenAPI conformance, by service DNS on the shared network

The pinned Schemathesis image runs as a one-shot container on the `apa` network and targets the
ch07 service by its compose service DNS name, not a host port:

```bash
docker run --rm --network apa -v "<repo>/contracts:/contracts:ro" \
  schemathesis/schemathesis:4.33.3 run /contracts/07-order-service.openapi.yaml \
  -u http://strict-self-describing-contract:8080 \
  -c status_code_conformance,content_type_conformance,response_schema_conformance
```

It generates requests from the committed contract and asserts the running service's status codes,
content types and response bodies all conform. The asserted signal is that no conformance failures
are reported. The report is written to a temporary path and never committed.

### Served versus committed OpenAPI

The harness fetches `/q/openapi` from the ch07 service, normalizes both the served document and the
committed contract to sorted-key JSON (so key order and JSON-versus-YAML formatting do not matter),
and asserts they are identical.

### MCP manifest conformance

The harness drives the ch13 server's MCP lifecycle over Streamable HTTP (`initialize`,
`notifications/initialized`, `tools/list`) and asserts the served tool set is identical to the
committed `contracts/13-order-tools.mcp.json`.

## The biting negative witness

A conformance check is only worth running if it fails when the contract and the service disagree.
Two committed drifted fixtures prove these checks discriminate, with no source edits to either
reused service:

- `contracts/27-order-service.drift.openapi.yaml` is the ch07 contract with `OrderResponse` tightened
  by a required `trackingCode` field the service never returns, and with the documented `400` and
  `404` problem responses removed, so the service's real error codes become undocumented.
- `contracts/27-order-tools.drift.mcp.json` is the ch13 manifest with the `orderStatus` tool renamed
  to `orderState` and a required parameter dropped from `paymentStatus`.

The harness runs the same Schemathesis conformance against the drifted OpenAPI and the same
`tools/list` comparison against the drifted manifest, and asserts both fail. The run passes only when
the real contracts conform and the drifted contracts are rejected.

## Run

```bash
./verify.sh
```

A fresh stack comes up (both reused services plus the shared infrastructure and LGTM), the four
conformance checks and the two drift witnesses run, and the stack is torn down. Set `KEEP=1` to leave
it running and probe by hand:

```bash
KEEP=1 ./verify.sh
curl -s http://localhost:8080/q/openapi | head
```

- Point Schemathesis at the drifted OpenAPI (`run /contracts/27-order-service.drift.openapi.yaml`)
  and watch the undocumented status codes turn up as failures.
- Diff the served `tools/list` against the drifted manifest and see the renamed tool.
- Edit a committed contract to disagree with its service and watch the matching check fail.

## Layout

| Path | Purpose |
|------|---------|
| `compose.yaml` | includes the shared infrastructure and builds ch07 and ch13 as two services on the `apa` network |
| `verify.sh` | the four conformance checks plus the two drift witnesses |
| `../../contracts/07-order-service.openapi.yaml` | the committed REST contract the ch07 service must conform to and serve |
| `../../contracts/13-order-tools.mcp.json` | the committed MCP tool manifest the ch13 server must advertise |
| `../../contracts/27-order-service.drift.openapi.yaml` | the drifted REST contract the conformance check must reject |
| `../../contracts/27-order-tools.drift.mcp.json` | the drifted MCP manifest the `tools/list` comparison must reject |
