---
title: "Running on Kubernetes with Strimzi"
order: 37
part: "Run in production"
description: "Stand up a self-serve Kubernetes platform and deploy a reused agent-facing service onto it unchanged, proving the cluster substrate with Strimzi Kafka, CloudNativePG Postgres and in-cluster Ollama."
duration: "40 minutes"
---

## Learning objectives

- Stand up a self-serve Kubernetes platform, tiered and health-gated, behind one fixed NodePort map.
- Deploy an existing service onto that platform unchanged, reading its configuration from a shared ConfigMap instead of a compose stack's own DNS.
- Assert a deployed service's observable effect through a platform NodePort and `kubectl exec`, never a forward, a tunnel or a proxy.
- Flip a cluster-native negative witness by changing a Deployment's environment, not its source, and watch the missed event recover once the flag is restored.

## Why this matters

Every pattern proven so far in this catalog ran on one Docker Compose stack: one network, one set of container names a compose file resolves by hand, one host port per service. Production rarely offers that shape. A service that is agent-facing on a developer's laptop has to stay agent-facing once it moves to a cluster with its own naming, its own mesh, and its own idea of what the network even is. If moving to Kubernetes changes how a service is built or how it decides what to do, the compose version and the cluster version stop being the same artifact, and every claim already proven about the first one no longer covers the second.

The test that matters here is not whether a cluster can run a container. It is whether an image already proven, on compose, to wake an agent on a Kafka event and write a durable record still does both once Kafka, Postgres and the model runtime are platform-managed cluster resources instead of compose services on first-name terms with each other.

{% include excalidraw.html file="37-deploying-onto-the-platform" alt="A reused service's Deployment and Service in the apa namespace, under Istio STRICT mTLS, reaching Strimzi Kafka, CloudNativePG Postgres and Ollama in apa-data by in-cluster DNS, with every host-facing check (kcat, kubectl exec psql, curl against Tempo, Prometheus and Loki) going through a platform NodePort instead of a forward" caption="Figure 37.1: Deploying a reused service onto the shared Kubernetes platform" %}

## Setting up the cluster

One minikube profile (docker driver, containerd runtime, Kubernetes v1.37.2, minikube v1.39.0) hosts the whole platform. Bring-up is tiered: each stage waits for the one before it to report healthy, so a Kafka cluster CR never applies against a Strimzi operator that is not yet ready, and an application Deployment never applies against a namespace that does not exist yet.

```bash
../_k8s/setup-profile.sh    # the minikube profile, NodePorts published on 127.0.0.1
../_k8s/bootstrap.sh         # CloudNativePG, Strimzi, otel-lgtm, Keycloak, Apicurio, Istio, Kiali, Ollama
```

`bootstrap.sh` installs CloudNativePG (operator v1.30.1) and a one-instance Postgres cluster, Strimzi (operator 1.3.1, Kafka 4.3.2, KRaft only) and a one-node Kafka cluster, a single `grafana/otel-lgtm:0.41.0` image for traces, metrics and logs, Keycloak (26.8.2) and Apicurio (3.3.4), Istio (1.32.0, STRICT mutual TLS on the application namespace) and Kiali (2.35.0), then Ollama with its pinned model pre-pulled onto a volume. Every host-facing endpoint is a NodePort fixed when the profile is created; there is no tunnel and no proxy at any layer.

| Endpoint | Host port |
|---|---|
| Kafka (external listener) | 127.0.0.1:9092 |
| PostgreSQL | 127.0.0.1:5432 |
| Grafana, Tempo, Prometheus, Loki | 127.0.0.1:3000, 3200, 19090, 3100 |
| Keycloak, Apicurio, Ollama | 127.0.0.1:8180, 8081, 11434 |

A chapter application's image builds on the host with `docker build` and loads directly into the profile's containerd runtime with `minikube image load`, never through a registry:

```bash
../_k8s/load-images.sh 17-event-triggered-agent-wakeup
```

The loaded image is named `localhost/apa/17-event-triggered-agent-wakeup:1.0.0`, matching the service's own Maven version, never a floating tag; its Deployment pins `imagePullPolicy: IfNotPresent`, so the kubelet only ever looks in the node's own image store. Applying its own kustomization deploys it:

```bash
kubectl apply -k ../_k8s/overlays/minikube/apps/17-event-triggered-agent-wakeup
```

That Deployment reads a shared ConfigMap for its Kafka bootstrap, its Postgres JDBC URL, its Ollama base URL and its OTLP endpoint, the same four values a compose file would otherwise set by container name; nothing about the service's code or its Flyway migration differs between the two targets.

## Build, run, observe

```bash
../_k8s/setup-profile.sh && ../_k8s/bootstrap.sh
../_k8s/load-images.sh 17-event-triggered-agent-wakeup
./verify.sh
```

Run from the deployed service's own directory, `verify.sh` redeploys it fresh, produces a payment-processed event against the platform's Kafka NodePort with `kcat`, confirms the woken agent's durable record through `kubectl exec ... psql` against the CloudNativePG pod, confirms a trace, a metric and a log line against Tempo, Prometheus and Loki's own NodePorts, then disables the consumer with a Deployment environment flip, confirms the next event sits unread, and restores it. It ends at 10/10.

Try:

- Run `KEEP=1 ./verify.sh`, then open Grafana on its NodePort and find the same trace and counter by hand.
- Flip the Deployment's consumer flag yourself with `kubectl set env` and watch the same negative witness bite outside the script.

## Anti-patterns

- **Re-forking a reused service for the cluster target.** A second copy that drifts from the original defeats the point: the platform is supposed to prove the same artifact, not a cluster-flavored rewrite of it.
- **Treating a ConfigMap change as if it were a code change.** If a Deployment needs a code edit to reach a cluster-managed dependency, the service was never actually environment-driven; fix the configuration surface, not the symptom.
- **A Prometheus metric's presence standing in for the negative witness.** A terminated pod's last-exported sample stays live in the time-series database well past a rollout, so a metric-name check alone can pass after the very behavior it claims to prove has stopped. Assert the durable row instead.
- **A forwarded or tunneled connection left running from a debugging session.** Reaching a pod directly instead of through the platform's own NodePort map bypasses the exact path a check is supposed to prove, and hides a check that would otherwise fail once the ad hoc connection is gone.

## References

- [Kubernetes documentation](https://kubernetes.io/docs/home/)
- [minikube documentation](https://minikube.sigs.k8s.io/docs/)
- [Strimzi documentation](https://strimzi.io/documentation/)
- [CloudNativePG documentation](https://cloudnative-pg.io/documentation/)
- [Istio documentation](https://istio.io/latest/docs/)
- [Kiali documentation](https://kiali.io/docs/)
