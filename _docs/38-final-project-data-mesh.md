---
title: "Final project: the focused multi-domain data mesh"
order: 38
part: "Run in production"
description: "Assemble three independently owned, agent-facing data products from already-built services onto the shared Kubernetes platform, and prove domain ownership, a self-serve platform and a live governance gate with one end-to-end scenario."
duration: "45 minutes"
---

## Learning objectives

- Assemble several already-built agent-facing services into one data mesh without re-forking a line of their code.
- Give each deployable its own database in a shared cluster, so no service reaches another's store and domain ownership is a checkable boundary instead of a diagram.
- Stand up a cross-cutting governance plane, a schema compatibility gate, a caller scope and a replayable audit obligation, that applies to every product instead of being built into any one of them.
- Prove an end-to-end agent-driven scenario across independently owned products by asserting captured side effects only, never a model's own narration.

## Why this matters

A catalog of separately built agent-facing services is not yet a mesh. Once two teams share a cluster, one service's database can leak into another's with nothing to stop it, and no single rule enforces schema compatibility across every contract an agent might encounter. The harder gap is proof: that an agent can walk from one product's read, through a payment, to another product's write, and find a record that matches what really happened rather than what a dashboard showed at the time.

The final project assembles three such products, orders, payments and shipping, from services already proven on their own, and asks one question of the result: when an agent reads from one product, writes through another, and the second product's effect depends on an event the first one caused, does the record each product keeps independently still add up to one consistent story? Domain ownership, a self-serve platform and a live governance gate only matter if they hold under that question, not just in how the cluster is described.

{% include excalidraw.html file="38-final-project-data-mesh" alt="Three independently owned data products, orders, payments and shipping, each with its own database and its own NodePort, on the shared Kubernetes platform, under one governance plane, with the payments-to-shipping event hop stood in for by a direct Kafka produce" caption="Figure 38.1: The final project's focused multi-domain data mesh" %}

## Deploying the mesh

Each service owns one database in the cluster's single Postgres instance, reset before the mesh starts and never touched by another service's Deployment. Orders stays read-only against a database it fully owns. Payments splits into an agent-facing facade with no database of its own and an internal service that owns the payments database alone, reached only by an in-cluster call, never from the host. Shipping splits the other way: a read port and an event-woken writer, each owning its own database, so neither can reach the other's store and their independent migration histories never collide.

Every product keeps the tool surface, the contract and the observability it already proved; what changes is only the environment each Deployment reads, from a shared configuration map on the same cluster the rest of the mesh runs on. One aggregate manifest applies all five Deployments and their Services together, so bringing up the whole mesh, or tearing down this layer alone, is one command, the self-serve platform underneath every product rather than a per-product chore.

A payment taken through the facade is supposed to cause a shipment. None of the reused services publishes that event on its own, so the scenario produces it directly against the cluster's own event broker, standing in for the output port a production payment service would own, and the catalog names that gap next to the product it belongs to. A governance plane sits across all three products rather than inside any one of them: a schema registered under a compatibility rule accepts a change that only adds an optional field and rejects one that adds a required field with no default, live, against the running registry, the same rejection a reader would hit trying to ship a breaking change to any product's contract. The same plane names, rather than enforces live against these particular services, a caller scope every write tool requires and an append-only audit record every inbound call and every outbound decision owes, so a production deployment of the same mesh has one rule to add per product rather than three to invent.

> **🤖 Explore with your agent**
> **Intent (stack-neutral):** an agent must discover and compose across data products that are independently owned, deployed and governed, not call into one monolith wearing three names.
> **Contract shape:** a read operation against one product's own output port, a write operation against a second product's own output port, an asynchronous effect the write causes that a third product reacts to, and an instructive error when a compatibility rule rejects a change, stated without a framework.
> **Build it here:** "Call the orders product's status tool for a known order, take a payment for a new one through the payments product's facade, and confirm the shipping product recorded a shipment for it once its own database shows the row."
> **Build it elsewhere:** "Implement the same three-product composition in Python/FastAPI (and Strawberry GraphQL): one read endpoint, one write endpoint behind its own service boundary, and a consumer reacting to the write's side effect, each owning its own data store."
> Check: a caller that only ever talks to each product's own output port can still observe one consistent order of events across all three.

## Build, run, observe

```bash
../_k8s/load-images.sh 13-package-api-as-mcp-tools 14-domain-split-progressive-discovery \
    15-reshape-grpc-behind-facade 17-event-triggered-agent-wakeup
kubectl apply -k ../_k8s/overlays/minikube/apps/38-data-mesh
./verify.sh
```

`verify.sh` creates the three databases, redeploys the mesh fresh, drives the orders read and a payment over their own NodePorts, stands in for the resulting event, confirms the woken writer's record and the shipping read, runs the live governance gate, then scales the writer to zero and back to prove a paid order can sit unshipped and recover. It ends at 23/23.

Try:

- Run `KEEP=1 ./verify.sh`, call the payments facade again by hand, and watch a second row land in its own database.
- Scale the shipping writer to zero yourself with `kubectl scale` and watch the same gap between a payment and a shipment appear outside the script.
- Register a backward-incompatible schema version by hand against the registry's own address and watch the same rejection the governance check already proves.

## Exercise the mesh yourself

Run `KEEP=1 ./verify.sh` first, so the mesh and its databases stay up once the script finishes. Every step below drives the same live mesh by hand, over the same NodePorts and the same `kubectl exec`, instead of only reading a pass count.

### 1. Domain ownership: one database per product

```bash
PG_POD=$(kubectl get pods -n apa-data -l cnpg.io/cluster=apa-postgres,cnpg.io/instanceRole=primary -o jsonpath='{.items[0].metadata.name}')
PG_PASS=$(kubectl get secret -n apa-data apa-postgres-app -o jsonpath='{.data.password}' | base64 -d)

psql_db() { # psql_db <database> <SQL>: password piped over stdin, SQL as a real argv
            # element of the remote command, never on a command line anyone could read
    printf '%s\n' "$PG_PASS" | kubectl exec -i -n apa-data "$PG_POD" -- \
        bash -c 'read -r pw; PGPASSWORD="$pw" exec psql -h 127.0.0.1 -U apa -d "$1" -v ON_ERROR_STOP=1 -tAc "$2"' \
        _ "$1" "$2"
}

psql_db shipping "SELECT order_id, carrier FROM shipping.shipment_prep"
psql_db orders   "SELECT order_id, carrier FROM shipping.shipment_prep"
```

The first call lists every row the event-woken writer has recorded so far. The second call runs the identical query against the `orders` database, which also has a schema named `shipping` (its own seeded copy for the composed read), and fails with `relation "shipping.shipment_prep" does not exist`. Same schema name, two different databases, only one of them holding that table.

Domain ownership is a boundary a query can hit, not a label on a diagram. Keep the `psql_db` function defined; the later steps reuse it.

### 2. Data as a product: call each output port directly

```bash
# orders: the MCP handshake, shown in full once
curl -sS -D /tmp/mcp-headers.txt -o /tmp/mcp-init.json \
    -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"reader","version":"1.0"}}}' \
    http://127.0.0.1:8088/mcp

SESSION=$(grep -i '^mcp-session-id:' /tmp/mcp-headers.txt | tr -d '\r' | cut -d' ' -f2)

curl -sS -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -H "Mcp-Session-Id: $SESSION" \
    -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
    http://127.0.0.1:8088/mcp

curl -sS -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -H "Mcp-Session-Id: $SESSION" \
    -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"orderStatus","arguments":{"orderId":"O-9001"}}}' \
    http://127.0.0.1:8088/mcp
```

The last response's `result.content[0].text` reports `orderStatus: SHIPPED`, `carrier: FASTLINE`, `trackingNumber: FL-778821`, the same seeded composed read the script checks. The handshake, an `initialize` call, the `Mcp-Session-Id` response header carried forward on every call after it, a `notifications/initialized` acknowledgement, then the tool call, is the same for every product; only the base URL, the path and the tool name change.

Repeat it against payments-facade (port 8089, same `/mcp` path) with `takePayment`:

```bash
curl -sS -D /tmp/mcp-headers.txt -o /tmp/mcp-init.json \
    -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"reader","version":"1.0"}}}' \
    http://127.0.0.1:8089/mcp
SESSION=$(grep -i '^mcp-session-id:' /tmp/mcp-headers.txt | tr -d '\r' | cut -d' ' -f2)
curl -sS -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -H "Mcp-Session-Id: $SESSION" -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
    http://127.0.0.1:8089/mcp
curl -sS -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -H "Mcp-Session-Id: $SESSION" \
    -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"takePayment","arguments":{"orderId":"O-50001","amount":"49.99"}}}' \
    http://127.0.0.1:8089/mcp
```

The response reports `orderId: O-50001`, `status: COMPLETED`, a write landing in the payments database alone, with no call to any other product.

And against shipping-track (port 8090, path `/mcp/shipping`) with `shipping_track_get`:

```bash
curl -sS -D /tmp/mcp-headers.txt -o /tmp/mcp-init.json \
    -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"reader","version":"1.0"}}}' \
    http://127.0.0.1:8090/mcp/shipping
SESSION=$(grep -i '^mcp-session-id:' /tmp/mcp-headers.txt | tr -d '\r' | cut -d' ' -f2)
curl -sS -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -H "Mcp-Session-Id: $SESSION" -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
    http://127.0.0.1:8090/mcp/shipping
curl -sS -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -H "Mcp-Session-Id: $SESSION" \
    -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"shipping_track_get","arguments":{"orderId":"O-7001"}}}' \
    http://127.0.0.1:8090/mcp/shipping
```

The response reports `carrier: FASTLINE`, `trackingNumber: FL-440219`.

A data product's contract is its own output port, never its database. Three handshakes against three different NodePorts prove three independently callable products, not one service wearing three names.

### 3. The agent-driven flow: a payment that wakes a write in another product

```bash
ORDER_ID="O-50002"

# take the payment through the facade (the same handshake as step 2, port 8089)
curl -sS -D /tmp/mcp-headers.txt -o /tmp/mcp-init.json \
    -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"reader","version":"1.0"}}}' \
    http://127.0.0.1:8089/mcp
SESSION=$(grep -i '^mcp-session-id:' /tmp/mcp-headers.txt | tr -d '\r' | cut -d' ' -f2)
curl -sS -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -H "Mcp-Session-Id: $SESSION" -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
    http://127.0.0.1:8089/mcp
curl -sS -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -H "Mcp-Session-Id: $SESSION" \
    -d "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"takePayment\",\"arguments\":{\"orderId\":\"$ORDER_ID\",\"amount\":\"149.99\"}}}" \
    http://127.0.0.1:8089/mcp

# stand in for the payments product's async output port: no reused service publishes this event,
# so produce it directly against the platform's own Kafka NodePort
printf '%s:{"id":"PAY-DEMO1","orderId":"%s","amount":149.99,"status":"COMPLETED"}\n' "$ORDER_ID" "$ORDER_ID" \
    | kcat -P -b 127.0.0.1:9092 -t apa.payments.processed -K:

# poll the shipping database for the write the woken agent makes
until [ -n "$(psql_db shipping "SELECT carrier FROM shipping.shipment_prep WHERE order_id = '$ORDER_ID'")" ]; do
    sleep 3
done
psql_db shipping "SELECT carrier FROM shipping.shipment_prep WHERE order_id = '$ORDER_ID'"
```

The payment completes in the payments database, the event lands on the topic, and within a few seconds a row for `$ORDER_ID` appears in the shipping database with `carrier` set to `FASTLINE` or `AIRCO`, whichever the live model decided. Assert the shape, never the exact value: the model's own choice can change between runs.

Nothing calls the shipping product directly. The only link between the payment and the write is the Kafka topic; this is the cross-product choreography the mesh exists to prove.

### 4. Federated governance: one compatibility rule across every product

```bash
GROUP="apa38-demo-$(date +%s)"
ARTIFACT="apa.audit.agent-action.v2"

# Apicurio's JSON Schema checker only resolves up to the draft-07 dialect, so rewrite
# $schema before posting
BASELINE=$(python3 -I -c '
import json, sys
with open(sys.argv[1], encoding="utf-8") as f:
    schema = json.load(f)
schema["$schema"] = "http://json-schema.org/draft-07/schema#"
print(json.dumps(json.dumps(schema)))
' ../../contracts/21-audit-agent-action.schema.json)

curl -sS -X POST "http://127.0.0.1:8081/apis/registry/v3/groups/$GROUP/artifacts" \
    -H 'Content-Type: application/json' \
    -d "{\"artifactId\":\"$ARTIFACT\",\"artifactType\":\"JSON\",\"firstVersion\":{\"content\":{\"content\":$BASELINE,\"contentType\":\"application/json\"}}}"

curl -sS -X POST "http://127.0.0.1:8081/apis/registry/v3/groups/$GROUP/artifacts/$ARTIFACT/rules" \
    -H 'Content-Type: application/json' \
    -d '{"ruleType":"COMPATIBILITY","config":"BACKWARD"}'
```

Both calls return the artifact and the rule just registered, `"config":"BACKWARD"` echoed back.

```bash
COMPATIBLE=$(python3 -I -c 'import json,sys; print(json.dumps(sys.stdin.read()))' <../../contracts/32-audit-agent-action.v2-compatible.schema.json)
curl -sS -w '\nHTTP %{http_code}\n' -X POST \
    "http://127.0.0.1:8081/apis/registry/v3/groups/$GROUP/artifacts/$ARTIFACT/versions" \
    -H 'Content-Type: application/json' \
    -d "{\"content\":{\"content\":$COMPATIBLE,\"contentType\":\"application/json\"}}"
```

An added optional field posts clean: HTTP 200 or 201.

```bash
BREAKING=$(python3 -I -c 'import json,sys; print(json.dumps(sys.stdin.read()))' <../../contracts/32-audit-agent-action.v2-breaking.schema.json)
curl -sS -w '\nHTTP %{http_code}\n' -X POST \
    "http://127.0.0.1:8081/apis/registry/v3/groups/$GROUP/artifacts/$ARTIFACT/versions" \
    -H 'Content-Type: application/json' \
    -d "{\"content\":{\"content\":$BREAKING,\"contentType\":\"application/json\"}}"
```

A new required field is rejected: an HTTP 400 with a JSON body whose `name` field reads `RuleViolationException`.

One rule, registered once against the shared registry, rejects the same breaking change coming from any of the three products; nobody has to remember to run a check per product.

### 5. The mesh-hop witness: scale one product down and watch the gap

```bash
kubectl scale deploy/event-triggered-agent-wakeup -n apa --replicas=0
kubectl rollout status deploy/event-triggered-agent-wakeup -n apa --timeout=120s
# Wait until the pod is fully gone before producing the event: a still-terminating
# consumer would read it during its grace period and the gap would never appear.
kubectl wait --for=delete pod -l app.kubernetes.io/name=event-triggered-agent-wakeup -n apa --timeout=120s

ORDER_ID="O-50003"
printf '%s:{"id":"PAY-DEMO2","orderId":"%s","amount":199.99,"status":"COMPLETED"}\n' "$ORDER_ID" "$ORDER_ID" \
    | kcat -P -b 127.0.0.1:9092 -t apa.payments.processed -K:

psql_db shipping "SELECT carrier FROM shipping.shipment_prep WHERE order_id = '$ORDER_ID'"
```

The query returns nothing. The order is paid, the event is sitting on the topic, and there is no shipment for it, paid but unshipped.

```bash
kubectl scale deploy/event-triggered-agent-wakeup -n apa --replicas=1
kubectl rollout status deploy/event-triggered-agent-wakeup -n apa --timeout=180s

until [ -n "$(psql_db shipping "SELECT carrier FROM shipping.shipment_prep WHERE order_id = '$ORDER_ID'")" ]; do
    sleep 3
done
psql_db shipping "SELECT carrier FROM shipping.shipment_prep WHERE order_id = '$ORDER_ID'"
```

Once the writer is back, it reads the event it missed and the row appears.

The gap is isolated to the one deployment that was down. Every other product, and the event itself, stays untouched; restoring one piece recovers the whole hop.

## Anti-patterns

- **One shared database behind three products.** A single schema a payments write and a shipping write can both reach erases the boundary domain ownership is supposed to draw; the visible symptom is a migration history collision, or worse, a quiet cross-product write nothing rejects.
- **A governance product instead of a governance plane.** Standing up a fourth deployable just to hold the compatibility rule and the audit obligation turns something cross-cutting into one more thing to deploy, version and keep in sync with the three it is supposed to govern.
- **A stood-in event producer left unmarked.** Driving an event directly against the broker to prove a consumer works is a reasonable stand-in for a producer that does not exist yet; leaving that fact undocumented lets a reader believe a production path exists where there is only a test harness.
- **Skipping the compatibility gate because nothing has broken yet.** A rule registered only after the first incompatible change has already shipped protects nothing; every consumer built against the old shape has already broken by the time anyone notices.

## References

- Zhamak Dehghani, *Data Mesh: Delivering Data-Driven Value at Scale*, O'Reilly, 2022
- Apicurio Registry documentation: [apicur.io/registry/docs](https://www.apicur.io/registry/docs/)
- Kubernetes documentation: [kubernetes.io/docs](https://kubernetes.io/docs/home/)
- CloudNativePG documentation: [cloudnative-pg.io/documentation](https://cloudnative-pg.io/documentation/)
- Model Context Protocol specification: [modelcontextprotocol.io](https://modelcontextprotocol.io/specification/2025-11-25)
