# Data mesh catalog: the final project's three products

A per-product descriptor, standing in for the discovery catalog a data mesh needs (OpenMetadata is
deferred): for each product, its domain owner, its output ports, its database, its Kafka topics,
its governance rule, and its NodePort. Every entity, event and topic name below comes from
`_plans/domain-model.md`; no product invents a new one.

## Orders

| Field | Value |
|---|---|
| Domain owner | order-service |
| Reused service | `examples/13-package-api-as-mcp-tools` |
| Output ports | MCP tools (`orderStatus`, `paymentStatus`, `shipmentTracking`): `contracts/13-order-tools.mcp.json`; MCP resources: `contracts/13-order-resources.mcp.json`; MCP prompts: `contracts/13-order-prompts.mcp.json`; OpenAPI: `contracts/07-order-service.openapi.yaml`; `llms.txt`: `contracts/08-llms.txt` |
| Database | `orders` (schemas `orders`, `payments`, `shipping`; read-only, seeded) |
| Kafka topics | none (a composed read, not an event participant) |
| Governance rule | Apicurio BACKWARD on any future schema registered for this product; Keycloak scope `mcp:tools:read` on every tool (read-only; no write scope needed); audit obligation: every tool call is an INBOUND record per `contracts/21-audit-agent-action.schema.json` |
| NodePort | 127.0.0.1:8088 to nodePort 30388, path `/mcp` |

## Payments

| Field | Value |
|---|---|
| Domain owner | payment-service |
| Reused service | `examples/15-reshape-grpc-behind-facade` (`mcp-facade` agent-facing; `payment-service` internal gRPC backend) |
| Output ports | MCP tools (`takePayment`, `refundPayment`): `contracts/15-payment-tools.mcp.json`; gRPC: `contracts/15-payment.proto`; async (stood in for, see below): topic `apa.payments.processed` |
| Database | `payments` (schema `payments`, owned by `payment-service` alone; the facade holds no state) |
| Kafka topics | produces `apa.payments.processed` (per `_plans/domain-model.md`'s event catalog; no reused service publishes it, so `examples/38-final-project-data-mesh/verify.sh` stands in for the producer with `kcat` against the Kafka NodePort, documented here rather than left silent) |
| Governance rule | Apicurio BACKWARD gate, proven live in `examples/38-final-project-data-mesh/verify.sh` against the cluster registry; Keycloak scope `mcp:tools:write` required on `takePayment` and `refundPayment` (neither reused service is OIDC-gated, so this is the named rule a production deployment enforces, not a live 403 here); audit obligation: every `takePayment`/`refundPayment` call is an INBOUND record, and the resulting `apa.payments.processed` event is itself the durable evidence of an OUTBOUND-adjacent domain effect, per `contracts/21-audit-agent-action.schema.json` and `contracts/34-outbound-call-audit.schema.json` |
| NodePort | 127.0.0.1:8089 to nodePort 30389, path `/mcp` (the facade only; `payment-service`'s gRPC port stays ClusterIP) |

## Shipping

| Field | Value |
|---|---|
| Domain owner | shipping-service |
| Reused services | `examples/14-domain-split-progressive-discovery` (read port, named MCP server `shipping`); `examples/17-event-triggered-agent-wakeup` (event-woken writer) |
| Output ports | MCP tools (`shipping_track_get`, `shipping_eta_get`): `contracts/14-shipping-tools.mcp.json`; shared catalog resource: `contracts/14-catalog.mcp.json`; async: `contracts/17-event-triggered-agent-wakeup.asyncapi.yaml` |
| Databases | `shipping` (the event-woken writer's `shipment_prep` table, owned by `examples/17-event-triggered-agent-wakeup` alone) and `shipping_track` (the read port's own seeded `orders`/`shipping` copies, owned by `examples/14-domain-split-progressive-discovery` alone); two separate databases, one per deployable, so the two independent Flyway histories never collide |
| Kafka topics | consumes `apa.payments.processed` (groupId `shipping-wakeup`, earliest offset) |
| Governance rule | Apicurio BACKWARD on any future schema registered for this product; Keycloak scope `mcp:tools:read` on the read port's tools; audit obligation: every `apa.payments.processed` record the writer consumes is the INBOUND trigger, and the carrier decision it writes to `shipment_prep` is the OUTBOUND record, per `contracts/21-audit-agent-action.schema.json` |
| NodePort | 127.0.0.1:8090 to nodePort 30390, path `/mcp/shipping` (the read port only; the writer has no host-facing tool surface, driven by the Kafka event and asserted through its database row) |

## Cross-cutting governance plane

Not a fourth product: a plane every product's write port sits under.

| Rule | Mechanism | Proven by |
|---|---|---|
| Schema compatibility | Apicurio Registry, `COMPATIBILITY` rule `BACKWARD`, per artifact | `examples/38-final-project-data-mesh/verify.sh`, reusing `examples/32-ci-for-agent-facing-apis`'s mechanism against the cluster registry's own NodePort |
| Caller authorization | Keycloak realm `apa`, client scopes `mcp:tools:read` and `mcp:tools:write` | named per product above; not a live 403 here, since none of the four reused services carries `quarkus-oidc` |
| Replayable audit | `AuditAgentAction` (`contracts/21-audit-agent-action.schema.json`), `OutboundCallAudit` (`contracts/34-outbound-call-audit.schema.json`) | named per product above |

## Self-serve platform

Every product above deploys through one aggregate kustomization,
`examples/_k8s/overlays/minikube/apps/38-data-mesh`, onto the one cluster
`examples/_k8s` builds and the one shared `apa-app-config` ConfigMap every Deployment reads
(`examples/_k8s/base/config.yaml`). No product stands up its own cluster, registry or identity
provider.

The `apa` namespace runs Istio with STRICT mutual TLS by default. A host request arriving through a
product's NodePort originates outside the mesh and carries no client certificate, so a workload
`PeerAuthentication` (`apps/38-data-mesh/peerauthentication.yaml`) sets the data products' HTTP tool
port (8080) to PERMISSIVE, which accepts both that host plaintext and in-mesh mTLS. Every other port
stays STRICT: the payments facade still reaches the internal `payment-service` over gRPC on 9000
under mutual TLS.
