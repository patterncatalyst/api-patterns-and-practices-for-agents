---
title: "Running on OpenShift Local (CRC)"
order: 40
marker: "Appendix B"
part: "Appendices"
description: "Run the final project's three agent-facing data products and their end-to-end scenario on OpenShift Local, unchanged except for one build profile, with every OpenShift-native mechanism confined to a single scaffolded layer."
duration: "60 minutes"
---

## Learning objectives

- State what moving the final project's data mesh onto OpenShift Local changes and what it leaves fixed: the application code, the images' contents and the environment contract stay the same; only a new `examples/_openshift/` layer differs.
- Translate each minikube-era mechanism, an image load, a NodePort, Strimzi, a raw custom resource, Istio installed by hand, to its OpenShift-native counterpart, and say why the translation exists.
- Explain the namespace-wide STRICT mutual TLS posture with a per-Route PERMISSIVE exception, and place it against the minikube path's port-level PERMISSIVE decision.
- Read a short known-issues table as evidence that a defect found on a live cluster run gets fixed in the scaffolded layer, not left as a caveat for the reader to work around.

## Why this matters

A pattern proven on minikube has proven itself against one kind of Kubernetes. Many production clusters are an on-premises OpenShift distribution instead: a different build path (no local image daemon to load from), a different edge (a Route terminating TLS, not a NodePort), a different operator surface (OperatorHub and OLM, not an operator installed from a raw manifest), and a different default security posture (every pod admitted under a pod security standard, no fixed UID). If the final project's data mesh only runs because minikube happens to offer some convenience no other cluster provides, the pattern was never portable: it was minikube-shaped.

The test here is narrower than standing up a cluster. The same three products, orders, payments and shipping, carry the same code, the same images' contents and the same ch38 scenario; only the layer around them translates. If that scenario and its mesh-hop negative witness still pass once the substrate is OpenShift, domain ownership, the governance gate and the agent-driven flow were never contingent on minikube's own conveniences.

{% include excalidraw.html file="ab-openshift-local" alt="S2I builds pushing ImageStreams into the apa project, edge-TLS Routes in front of orders, payments-facade and shipping-track, OpenShift Service Mesh 3 enforcing namespace-wide STRICT mutual TLS with a PERMISSIVE PeerAuthentication on each Route-facing service, and the five data-mesh deployables reaching AMQ Streams Kafka, a per-product Postgres and Ollama" caption="Figure B.1: The OpenShift-native topology behind the same final-project data mesh" %}

## What stays the same, what moves

Every Maven module, every route, every Flyway migration and every test in the five reused deployables, orders (chapter 13), the payments facade and its internal gRPC backend (chapter 15), the shipping read port (chapter 14) and the event-woken writer (chapter 17), is byte-identical to the minikube and compose runs. The one addition is an `openshift` Maven profile carrying the `quarkus-openshift` dependency; nothing in `application.properties` or a service's own code changes for it. The ConfigMap keys, Service names and Secret names a Deployment reads stay the same contract the other two runtimes already honor, so the only thing that differs between "the same service on minikube" and "the same service on OpenShift Local" is which value fills that contract, not its shape.

What moves lives entirely in `examples/_openshift/`: the build mechanism, the edge, the operator installation method and the default pod security posture. OpenShift-native code never leaks into a service's own source, its Containerfile convention, or the ch38 scenario's assertions; the scenario a reader already knows from the final project is the same scenario this appendix verifies, over a different front door.

### S2I builds, not an image load

minikube's path is `docker build` on the host, then `minikube image load` straight into the node's own containerd. OpenShift Local has no equivalent shortcut and no exposed registry to push to by hand: the build happens inside the cluster. `quarkus-openshift` packages each service's fast-jar as a binary Source-to-Image build against the `ubi10/openjdk-25` builder image, and the build itself produces an ImageStream tag in the project; the host never runs a container engine and the cluster never reaches out to Maven Central.

The one trap in that translation is naming. `quarkus-openshift` names the BuildConfig and the ImageStream after `quarkus.openshift.name`, which defaults to the Maven artifactId, never after `quarkus.container-image.name`. Every module's own artifactId differs from its data-mesh product name (the gRPC facade module is `mcp-facade`, deployed here as `payments-facade`), so `build-images.sh` overrides `quarkus.openshift.name` explicitly to match the chart's service keys.

### Edge-TLS Routes, not NodePorts

minikube exposes each host-facing product on a fixed NodePort, published straight to `127.0.0.1`. OpenShift Local's edge is the router: a Route terminates TLS at the cluster boundary and forwards plain HTTP to the Service behind it. The three products a caller reaches from outside the cluster, orders, the payments facade and the shipping read port, each get a Route with edge termination and a plain-HTTP redirect; the internal gRPC backend and the Kafka-woken writer get no Route at all, reached only by their own Service name in-cluster or by `oc exec`.

Verifying a Route never uses `--insecure`. `capture-evidence.sh` reads the cluster's own ingress CA out of `openshift-config-managed` and hands it to curl as `--cacert`, so a passing check proves the certificate the router actually serves, not merely that some certificate was presented.

### AMQ Streams and OLM operators, not Strimzi and raw CRs

minikube applies Strimzi's CRDs and a Kafka custom resource directly from the project's own manifests. On OpenShift Local, Kafka comes from the AMQ Streams operator, the same project under a Red Hat-supported name, installed the way every operator on this cluster is installed: a Subscription pinned to an exact ClusterServiceVersion with `installPlanApproval: Manual`, and only the one InstallPlan naming that CSV approved. An operator upgrade OLM proposes on its own sits unapproved until the pin in `examples/_openshift/infra/amq-streams-subscription.yaml` moves, so the Kafka version running underneath the mesh never shifts without an explicit edit next to the pin.

Postgres follows the same spirit without an operator: no OperatorHub entry for CloudNativePG, the operator the other Kubernetes runtime uses, installs cleanly under this cluster's pod security defaults, so the chart runs Postgres as a plain StatefulSet on the Red Hat `rhel10/postgresql-16` image instead, built for an arbitrary UID and reachable under the exact restricted posture every other pod runs under.

### STRICT mutual TLS, with a per-Route PERMISSIVE exception

OpenShift Service Mesh 3 (the Sail operator's build of Istio) puts a namespace-wide `PeerAuthentication` of `STRICT` over the apa project: a meshed pod accepts only mTLS, and a plaintext call from outside the mesh is reset before it reaches the application. That default is correct for every in-cluster call, the payments facade's gRPC call to its backend, a service reading its own Postgres database, right up until a Route tries to reach a meshed pod. The OpenShift router is not itself in the mesh, so what a Route delivers to orders, the payments facade or the shipping read port is plaintext HTTP; against a STRICT sidecar that plaintext gets a 502 on every single request, not an occasional one.

The fix is a workload-scoped `PeerAuthentication` set to `PERMISSIVE`, one per Route-facing service, selecting only that service's own pods. PERMISSIVE accepts both the router's plaintext and actual in-mesh mTLS, so nothing already inside the mesh is downgraded; the two internal deployables, the payment-service backend and the shipping writer, carry no such exception and stay STRICT, reachable only by another meshed caller.

This is the same trade-off the minikube path already makes, stated differently because the two platforms split their host-facing traffic differently. minikube reaches every data-mesh pod's HTTP port directly over its own NodePort, so one workload PeerAuthentication there sets port 8080 PERMISSIVE across all five pods and leaves every other port, in particular the payments facade's gRPC call, STRICT. OpenShift has no single port shared by host traffic and in-mesh traffic in that way; instead, only three of the five deployables ever terminate a Route at all, so the exception there is scoped to those three identities instead of to a port number. Either way the rule is the same: grant PERMISSIVE only where an edge outside the mesh must land, and leave every other path, gRPC calls, database reads, Kafka consumption, under STRICT.

## Setting up the cluster

OpenShift Local needs to be sized before it starts; sizing only takes effect while the instance is stopped. The platform tier (AMQ Streams, OSSM 3, Kiali and Ollama alongside the five deployables) needs more headroom than the core tier alone.

```bash
crc config set cpus 12
crc config set memory 32768
crc start
eval "$(crc oc-env)"
```

```bash
./openshift/install-infra.sh      # the apa project, AMQ Streams, a Kafka cluster
./openshift/build-images.sh       # S2I builds, one per service, ImageStreams pushed
./openshift/deploy.sh             # the Helm chart: Postgres, Apicurio, Ollama, the 5 Deployments, Routes
./openshift/capture-evidence.sh   # the scenario, the pod/Route/security checks, a secret scrub
./openshift/platform/install-mesh.sh   # optional: OSSM 3 + Kiali, STRICT mTLS, the Route PERMISSIVE split
./openshift/teardown.sh           # every operator, CRD, namespace and workload removed, then crc stop
```

This run was verified on CRC 2.64.0 against OpenShift 4.22.14, with the AMQ Streams operator at `amqstreams.v3.2.2-9` (Kafka 4.2.0), OpenShift Service Mesh 3 at `servicemeshoperator3.v3.5.0` (Istio 1.30.5) and the Kiali operator at `kiali-operator.v2.33.1`. Each service's S2I build runs on the `ubi10/openjdk-25` builder image. Ollama serves `qwen3:4b-instruct-2507-q4_K_M` at image tag `0.40.16`, the same model the shipping writer's advisor is tested against on every other runtime. Apicurio Registry runs at `3.3.4`, the same version and the same in-memory storage mode the compose and minikube runtimes already use.

## Build, run, observe

`capture-evidence.sh` drives the same scenario the final project already proves, over the OpenShift-native front door: orders composes a status across all three products' own databases through its Route; a payment taken through the payments facade's Route lands a completed row in payment-service's own database; the shipping read port answers its seeded tracking lookup through its own Route. A payment event produced directly against the in-cluster Kafka broker wakes the shipping writer, which calls Ollama and writes a durable shipment record. The same mesh-hop negative witness from the final project holds here too: scaling the writer to zero leaves a paid order with no shipment row, and restoring it lets the writer catch up on the missed event. The Apicurio registry, reached through its own Route, accepts a schema version that only adds an optional field and rejects one that adds a required field with no default, a live gate rather than a described one.

With the platform tier installed, STRICT mutual TLS rejects a plaintext call from outside the mesh against an internal service, and Kiali reports the namespace's mTLS status as fully enabled, the same trust claim the final project already makes, now backed by OpenShift's own mesh control plane.

Try:

- Call the payments facade's Route a second time once a run finishes and watch a second row land in payment-service's own database.
- Scale the shipping writer to zero with `oc scale` and reproduce the same unshipped order the scripted witness already proves.

## What broke

| Symptom | Cause | Fix |
|---|---|---|
| The payments facade and payment-service ImageStreams came out named after Maven module directories, not the chart's service names | `quarkus-openshift` names the BuildConfig and ImageStream after `quarkus.openshift.name` (default: the artifactId), never after `quarkus.container-image.name` | `build-images.sh` passes `-Dquarkus.openshift.name=<service>` explicitly, matching the chart's `services:` keys |
| A plain `deploy.sh` already needed Ollama and the shipping writer's model-backed decision, not just the three read/write products | The event-woken agent and its Ollama-backed carrier decision are core to the final project's scenario, not an optional extra | The chart's `ai.enabled` defaults to true, so a plain install brings up Ollama and the writer together |
| A Route-facing workload under STRICT mTLS answers the router's request with a 502 | The OpenShift router sits outside the mesh and delivers plaintext HTTP; a STRICT sidecar resets a plaintext connection | A workload `PeerAuthentication` set to PERMISSIVE for each Route-facing service, STRICT left in place for the internal services |

## Anti-patterns

- **A namespace-wide PERMISSIVE instead of a scoped one.** Setting the namespace default itself to PERMISSIVE to clear the Route 502s turns off the guarantee for every internal call too; the payments facade's own gRPC call to its backend would then also accept plaintext, which is exactly the hop STRICT exists to protect.
- **Reaching a pod's own IP or its internal Service instead of its Route.** A direct call bypasses both the edge TLS a Route terminates and the exact PERMISSIVE exception scoped to Route traffic; a check that passes this way can still fail for a real caller going through the router.
- **Approving every InstallPlan an operator proposes.** `installPlanApproval: Manual` exists so a Kafka or mesh upgrade never lands silently; approving an InstallPlan this project never named moves a pin nobody reviewed.
- **Skipping the ingress-CA fetch and falling back to `--insecure`.** A check that accepts any certificate proves nothing about the router's real TLS configuration; it would still pass against a misconfigured or expired one.

## References

- [OpenShift Local (CRC) documentation](https://crc.dev/docs/)
- [OpenShift Service Mesh 3 documentation](https://docs.redhat.com/en/documentation/red_hat_openshift_service_mesh/3.0)
- [AMQ Streams documentation](https://access.redhat.com/documentation/en-us/red_hat_amq_streams)
- [Red Hat build of PostgreSQL container images](https://catalog.redhat.com/software/containers/rhel10/postgresql-16)
