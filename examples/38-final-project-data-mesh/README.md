# 38 Final project: the focused multi-domain data mesh

Assembles the final project's data mesh on the shared Kubernetes platform (`examples/_k8s`) from
four already-built services, reused unchanged: `examples/13-package-api-as-mcp-tools`
(orders), `examples/14-domain-split-progressive-discovery` (shipping read), `examples/15-reshape-
grpc-behind-facade` (payments, MCP facade plus an internal gRPC backend), and `examples/17-event-
triggered-agent-wakeup` (shipping write, woken by a Kafka event). No app code changes; only the
database each points at and two new environment values differ.

Three agent-facing data products come out of five Deployments:

| Product | Deployments | Database | Output port |
|---|---|---|---|
| Orders | `orders` | `orders` | MCP `/mcp` (NodePort) |
| Payments | `payments-facade`, `payment-service` | `payments` | MCP `/mcp` (NodePort); gRPC internal |
| Shipping | `shipping-track`, `event-triggered-agent-wakeup` | `shipping` | MCP `/mcp/shipping` (NodePort); Kafka consumer internal |

## What this proves

- **Domain ownership** (Dehghani principle 1): each product's database is created idempotently by
  this script, owned by the `apa` role, and no other product's Deployment points at it. Shipping's
  two Deployments share one database in two schemas (`orders` for history, `shipping` for the
  writer's own record), proven not to collide by their own Flyway migrations.
- **Data as a product**: every product keeps its committed contract (the reused services' own MCP
  tool manifests and the payments gRPC `.proto`), its own NodePort, and reaches the platform's
  observability stack over OTLP.
- **A self-serve platform**: the same `examples/_k8s` cluster, the same shared `apa-app-config`
  ConfigMap, one aggregate kustomize overlay that deploys the whole mesh in one command.
- **Federated computational governance**: a live Apicurio BACKWARD compatibility gate (the same
  mechanism `examples/32-ci-for-agent-facing-apis` built) accepts a compatible schema version and
  rejects an incompatible one against the cluster's own registry.
- The biting mesh-hop witness: scaling the shipping writer to 0 leaves a paid order unshipped, with
  no shipment record; restoring it reads the missed event.

## Run

Bring the platform up first if it is not already running, and load every mesh image (including
`examples/15-reshape-grpc-behind-facade`'s two sub-module images):

```bash
../_k8s/setup-profile.sh
../_k8s/bootstrap.sh
../_k8s/load-images.sh 13-package-api-as-mcp-tools 14-domain-split-progressive-discovery \
    15-reshape-grpc-behind-facade 17-event-triggered-agent-wakeup
```

The mesh's three new host NodePorts (orders, payments, shipping-track) are fixed in
`../_k8s/lib/endpoints.sh`; they take effect once the profile has been created or replaced
(`../_k8s/setup-profile.sh --replace`) after this file landed.

Then:

```bash
./verify.sh
```

`verify.sh` checks the platform is Ready, creates the three per-product databases if they do not
already exist, deletes and reapplies the mesh's own kustomization (`../_k8s/overlays/minikube/apps/
38-data-mesh`), waits for all 5 rollouts, drives the orders read and a payment over their NodePorts,
produces the resulting payments-processed event with `kcat` (standing in for the payments product's
async output port, since no reused app publishes it), confirms the woken shipping writer's durable
record and the shipping read, confirms the writer's observability signals, runs the live governance
gate against the cluster Apicurio, runs the biting mesh-hop negative witness, and removes the mesh's
Deployments and Services again unless `KEEP=1` is set. The shared platform is never torn down by
this script.

## Expected output

```
PASS 1: the three per-product databases exist, each owned by apa (orders, payments, shipping)
PASS 2: the data mesh applied fresh across all 5 deployments in the apa namespace
PASS 3: orders rolled out Ready
PASS 4: payments-facade rolled out Ready
PASS 5: payment-service rolled out Ready
PASS 6: shipping-track rolled out Ready
PASS 7: shipping-writer rolled out Ready, now owning the shipping database
PASS 8: ORDERS: orderStatus composes the seeded O-9001 from all three schemas in the orders database
PASS 9: PAYMENTS: takePayment over the facade NodePort completes for O-38001
PASS 10: PAYMENTS: payment-service writes a COMPLETED payments.payments row for O-38001 in the payments database
PASS 11: the payments-processed event for O-38001 reaches the Kafka NodePort (stands in for payments' async output port)
PASS 12: SHIPPING: the woken writer records a shipment prep for O-38001 with a valid carrier (kubectl exec psql, not model prose)
PASS 13: Tempo has a trace for service event-triggered-agent-wakeup
PASS 14: Prometheus has a metric from event-triggered-agent-wakeup
PASS 15: Loki has the shipping writer's own log line
PASS 16: SHIPPING: shipping_track_get over its NodePort returns the seeded O-7001 tracking (FASTLINE/FL-440219)
PASS 17: governance: a schema is registered in the cluster Apicurio under the BACKWARD compatibility rule
PASS 18: governance: a BACKWARD-compatible next version (an added optional field) is accepted
PASS 19: governance: a BACKWARD-incompatible version (a new required field) is rejected
PASS 20: mesh-hop witness: shipping writer scaled to 0 and rolled out
PASS 21: mesh-hop witness: a paid order (O-38002) with the writer down leaves no shipment_prep row (paid but unshipped)
PASS 22: mesh-hop witness: shipping writer restored to 1 replica and rolled out
PASS 23: mesh-hop witness: restored, the missed event is now consumed and the row appears for O-38002
23/23 checks passed
```

This run is pending an orchestrator cluster lane; `_plans/verification-log.md` and
`_plans/claims.md` carry the recorded result once it has run live (status `Pending` until then).

## Layout

| Path | Purpose |
|------|---------|
| `verify.sh` | the cluster-lane checks above; no app code of its own |
| `../_k8s/overlays/minikube/apps/38-data-mesh/` | the mesh's 5 Deployments, 4 Services and aggregate kustomization |
| `../_k8s/overlays/minikube/apps/17-event-triggered-agent-wakeup/` | the shipping writer's own Deployment and Service, reused via a kustomize resource reference plus one patched env var |
| `../_k8s/load-images.sh` | builds and loads all 5 images, including the two from the 15 sub-modules |
| `../13-package-api-as-mcp-tools/`, `../14-domain-split-progressive-discovery/`, `../15-reshape-grpc-behind-facade/`, `../17-event-triggered-agent-wakeup/` | the reused services: source, Containerfiles, Flyway migrations |
| `../../contracts/38-data-mesh-catalog.md` | the per-product descriptor: owner, output ports, database, topics, governance rule, NodePort |

## Why no new Containerfile or source

Every image this mesh deploys already exists; building a new one for the final project would fork
services this catalog already proved, for no behavioral difference. The mesh demonstrates
composition across independently owned products, not new application code.
