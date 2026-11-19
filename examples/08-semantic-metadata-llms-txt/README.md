# 08 Semantic metadata, llms.txt and the dual-use portal

An order-service that proves chapter 08's thesis: when a machine-readable surface and a
human-readable surface describe the same API from two independent write-ups, they drift, not if,
when. `ApiMetadata` is the one CDI bean that owns every operation's id, HTTP method, path and
summary. `LlmsTxtResource` renders `/llms.txt` from it in the llmstxt.org shape (an H1 title, a
blockquote summary, then `##` sections with markdown links); `PortalResource` renders a minimal
Qute-templated HTML portal at `/portal` from the same bean. Neither resource keeps its own copy
of the operation list. `placeOrder` and `getOrder` are served by Quarkus REST, with Camel on the
event edge publishing `OrderPlaced`, mirroring the other agent-facing order-service examples in
this book; `quarkus-smallrye-openapi` is present so both surfaces can link to `/q/openapi`.

## The negative witness

The witness is "the two surfaces can only drift if something stops reading from the shared
bean." To confirm, `LlmsTxtResource.llmsTxt()` was changed to append a hardcoded extra operation
line (`cancelOrder`, which does not exist in `ApiMetadata` and which `PortalResource` never
renders) after the loop over `metadata.operations()`. With the bean bypassed for that one line,
`mvn -B verify` failed `portalAndLlmsTxtNameTheSameSetOfOperations` (`llms.txt` named
`[cancelOrder, getOrder, placeOrder]`, the portal still named only `[getOrder, placeOrder]`), and
a live `./verify.sh` run fell to 15/17, failing the set-equality check and the diff against the
committed `contracts/08-llms.txt` (the regenerated file no longer matched what was checked in).
The extra line was removed and the suite reverified green (`mvn -B verify` 5/5, `./verify.sh`
17/17), reproducing the same served `llms.txt`.

## Run

```bash
./verify.sh
```

`verify.sh` needs Docker Engine with the compose plugin and `jq`; it builds the service and the
shared domain model inside the image, starts Kafka, PostgreSQL, Apicurio, Keycloak, Ollama and
LGTM plus the service, runs the checks below and removes everything with `down -v`. Set
`KEEP=1` to leave the stack running for inspection.

Run the tests alone (Quarkus Dev Services starts Kafka and PostgreSQL containers; no compose
stack needed). Install the shared domain model first:

```bash
(cd ../domain-model && mvn -B -q install)
mvn -B verify
```

## Expected output

```
PASS 1: apa-kafka is healthy
...
PASS 7: apa-semantic-metadata-llms-txt is healthy
PASS 8: placing an order succeeds with 201
PASS 9: getting the order just placed succeeds with 200
PASS 10: /llms.txt is served with 200
PASS 11: /llms.txt is text/plain
PASS 12: /llms.txt is well-formed (H1, blockquote summary, ## sections, markdown links)
PASS 13: /portal is served with 200
PASS 14: /portal is text/html
PASS 15: the operation sets extracted from llms.txt and the portal are both non-empty
PASS 16: llms.txt and the portal name exactly the same set of operations
PASS 17: the served llms.txt matches the committed contracts/08-llms.txt
17/17 checks passed
```

## Tests and checks in detail

`SemanticMetadataLlmsTxtTest` (`mvn -B verify`) exercises the whole service against Dev Services
Kafka and PostgreSQL: `placeOrder` and `getOrder` work; `/llms.txt` is served as `text/plain` and
opens with an H1 title, a blockquote summary, and the `## API reference` and `## Operations`
sections; `/portal` is served as `text/html`; and the operation ids extracted from `/llms.txt`'s
markdown links and the portal's `data-op` attributes are exactly equal.

`verify.sh` adds the live, container-level view of the same checks, plus a byte-for-byte diff of
the served `/llms.txt` against the committed `contracts/08-llms.txt`, which the unit test does
not check.

## Experiments

Each row changes a single thing and names the checks that fail.

| Switch | Effect | Failing checks |
|---|---|---|
| Append a hardcoded operation line to `LlmsTxtResource.llmsTxt()` that bypasses `ApiMetadata` | `llms.txt` names an operation the portal does not | 16, 17 |
| Remove an operation from `PortalResource`'s rendering instead (not exercised here) | The portal would name fewer operations than `llms.txt` | 16, 17 |

## Layout

| Path | Purpose |
|------|---------|
| `src/main/java/.../ApiMetadata.java` | the single source of operation metadata |
| `src/main/java/.../OperationInfo.java` | one operation's id, method, path and summary |
| `src/main/java/.../LlmsTxtResource.java` | renders `/llms.txt` from `ApiMetadata` |
| `src/main/java/.../PortalResource.java`, `src/main/resources/templates/portal.html` | renders the Qute-templated human portal from the same bean |
| `src/main/java/.../OrderResource.java`, `OrderService.java`, `PlaceOrderRoute.java` | the agent-facing order-service surface (`placeOrder`, `getOrder`), mirroring the other examples in this book |
| `contracts/08-llms.txt` (repo root) | the committed, served `llms.txt` |
| `compose.yaml` | includes the shared infrastructure and adds the service |
| `Containerfile` | UBI 10 multi-stage build; context is `examples/` |
| `verify.sh` | the checks above |
