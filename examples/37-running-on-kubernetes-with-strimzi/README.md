# 37 Running on Kubernetes with Strimzi

Proves the shared Kubernetes platform (`examples/_k8s`) end to end by deploying one existing
service onto it unchanged: `examples/17-event-triggered-agent-wakeup`, the event-triggered agent
wakeup example. Its image is reused as-is; nothing about the service's code, configuration keys or
Flyway migration changes. Only the environment it reads from changes, from compose's service-name
DNS to the platform's shared `apa-app-config` ConfigMap, which is exactly the point: the same image
runs on both targets with no rebuild.

The service's own pattern (a Camel Kafka consumer wakes an agent the instant a payment-processed
event arrives, and the agent's one tool call writes a durable record) now has to hold through real
platform pieces instead of compose containers: Strimzi-managed Kafka, a CloudNativePG-managed
Postgres, and an in-cluster Ollama, all reached the same way the service always reached them, by
DNS name inside the cluster.

## What this proves

- The shared platform's env contract (`examples/_k8s/base/config.yaml`) resolves to a real,
  reachable Kafka bootstrap, Postgres and Ollama endpoint from inside a pod.
- A chapter app's Deployment and Service, sidecar-injected under the namespace-wide Istio STRICT
  mTLS policy, starts, passes its health probes and reaches every platform dependency without any
  change to the mesh or the app.
- The biting negative witness translates to the cluster: a Deployment env var flip, not a source
  edit, stops the consumer; the missed event is never recorded; restoring the flag reads it.
- Every host-facing assertion goes through a platform NodePort or `kubectl exec`, never a forward,
  tunnel or proxy of any kind.

## Run

Bring the platform up first if it is not already running:

```bash
../_k8s/setup-profile.sh
../_k8s/bootstrap.sh
../_k8s/load-images.sh 17-event-triggered-agent-wakeup
```

Then:

```bash
./verify.sh
```

`verify.sh` checks the platform is Ready (it does not bring it up itself), deletes and reapplies
this app's own Deployment and Service (`../_k8s/overlays/minikube/apps/17-event-triggered-agent-
wakeup`), waits for the rollout, drives the trigger with `kcat` against the platform's Kafka
NodePort, asserts the durable record with `kubectl exec ... -- psql` against the CloudNativePG
pod, asserts Tempo, Prometheus and Loki signals against their own NodePorts, runs the biting
negative witness, and removes this app's Deployment and Service again unless `KEEP=1` is set. The
shared platform is never torn down by this script; use `../_k8s/teardown.sh` for that separately.

## Expected output

```
PASS 1: event-triggered-agent-wakeup deployed fresh and rolled out Ready in the apa namespace
PASS 2: the woken agent records a shipment prep for O-91001 (observed via kubectl exec psql, not model prose)
PASS 3: Tempo has a trace for service event-triggered-agent-wakeup
PASS 4: Prometheus has a metric from event-triggered-agent-wakeup
PASS 5: Prometheus has the prepShipment tool-call counter
PASS 6: Loki has the tool's own log line (observed, not model prose)
PASS 7: consumer disabled (WAKEUP_CONSUMER_ENABLED=false) and rolled out
PASS 8: negative witness: a disabled consumer leaves the event unconsumed (no shipment_prep row for O-91002)
PASS 9: consumer restored (WAKEUP_CONSUMER_ENABLED=true) and rolled out
PASS 10: restored: the consumer that was disabled now records O-91002's shipment prep
10/10 checks passed
```

This run record is provisional until the orchestrator's cluster lane executes `verify.sh` against
a live profile; `_plans/verification-log.md` carries the confirmed result.

## Layout

| Path | Purpose |
|------|---------|
| `verify.sh` | the cluster-lane checks above; no app code of its own |
| `../_k8s/overlays/minikube/apps/17-event-triggered-agent-wakeup/` | this app's Deployment, Service and kustomization |
| `../_k8s/load-images.sh` | builds and loads `localhost/apa/17-event-triggered-agent-wakeup:1.0.0` |
| `../17-event-triggered-agent-wakeup/` | the reused service: source, Containerfile, Flyway migration |

## Why no new Containerfile or source

The service's UBI 10 multi-stage Containerfile already builds the exact image this chapter
deploys; a second copy would be a fork of the same code for no behavioral difference. The platform
proves itself by running something real, not a purpose-built smoke-test service.
