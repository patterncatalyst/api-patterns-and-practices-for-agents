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
