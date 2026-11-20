# examples/_k8s

The shared Kubernetes platform for the final project (Part 7): one minikube
profile, tiered and health-gated bring-up, a fixed host-port map and the
kustomize base a chapter app lands on. The setup chapter that proves this
platform end to end deploys one reused service onto it; see
`overlays/minikube/apps/`.

## Bring-up

From this directory:

```bash
./setup-profile.sh     # create the minikube profile (docker driver, containerd)
./bootstrap.sh          # stand up the full platform, tiered and health-gated
```

`bootstrap.sh` is the one command that takes an empty profile to a running
platform. It calls each `setup-*.sh` script in order, waiting for one tier to
report healthy before starting the next:

1. minikube profile + namespaces
2. CloudNativePG operator + Postgres cluster CR
3. Strimzi operator + Kafka cluster CR
4. otel-lgtm (observability)
5. Keycloak
6. Apicurio
7. Istio (mesh + STRICT mTLS on the `apa` namespace)
8. Kiali (operator + CR, wired to otel-lgtm)
9. Ollama (plus a one-shot Job that pre-pulls the pinned model)

Every script is idempotent (`helm upgrade --install`, `kubectl apply`,
`kubectl wait`/`rollout status`): re-running `bootstrap.sh` after an
interrupted run resumes safely, and running a single `setup-*.sh` script on
its own brings just that tier up to date.

Recreate the profile from scratch at any point:

```bash
./setup-profile.sh --replace    # deletes and recreates the cluster
./bootstrap.sh                   # run again afterwards
```

## Namespaces

| Namespace | Holds | Mesh |
|---|---|---|
| `apa` | the env-contract ConfigMap now; chapter app Deployments in a later wave | labeled for namespace-wide Istio injection |
| `apa-data` | Postgres cluster CR, Kafka cluster CR, Keycloak, Apicurio, Ollama | not meshed |
| `observability` | the single otel-lgtm Deployment | not meshed |
| `istio-system` | istiod, the kiali-operator and its CR | mesh control plane |
| `cnpg-system` | the CloudNativePG operator (cluster-wide) | not meshed |

Namespace-wide Istio injection on `apa` only, not the whole platform, is a
deliberate choice: a sidecar on a one-shot Job pod never lets that pod exit,
and a database operator's own TLS handshake to its managed pod collides with
a sidecar's mTLS wrapping. Keeping Postgres, Kafka, Keycloak, Apicurio and
Ollama in the unmeshed `apa-data` namespace means neither failure mode can
happen; only application pods, which have neither problem, are meshed.

## Host access: the endpoint map

Every host-facing port is a NodePort, fixed and published on `127.0.0.1`
when the profile is created (`minikube start --ports=...`). There is no
tunnel, no relay and no host network change of any kind; `./show-endpoints.sh`
prints the live status of every row below.

| Service | Host port | NodePort | Owner |
|---|---|---|---|
| Grafana | 3000 | 30300 | `setup-lgtm.sh` + `overlays/minikube` |
| OTLP gRPC | 4317 | 30317 | `setup-lgtm.sh` + `overlays/minikube` |
| OTLP HTTP | 4318 | 30318 | `setup-lgtm.sh` + `overlays/minikube` |
| Loki | 3100 | 30310 | `setup-lgtm.sh` + `overlays/minikube` |
| Tempo | 3200 | 30320 | `setup-lgtm.sh` + `overlays/minikube` |
| Prometheus | 19090 | 30909 | `setup-lgtm.sh` + `overlays/minikube` |
| Kafka (external listener, for kcat and similar) | 9092 | 30920 | `setup-kafka-operator.sh` (Strimzi-owned) |
| PostgreSQL | 5432 | 30543 | `setup-postgres-operator.sh` + `overlays/minikube` |
| Apicurio | 8081 | 30808 | `setup-apicurio.sh` + `overlays/minikube` |
| Keycloak | 8180 | 30818 | `setup-keycloak.sh` + `overlays/minikube` |
| Ollama | 11434 | 31143 | `setup-ollama.sh` + `overlays/minikube` |
| Kiali | 20001 | 30201 | `setup-kiali.sh` (kiali-operator CR) |

Host ports match the pins in the book's root `CLAUDE.md` and
`examples/_infra/compose.yaml`; Kiali's host port (20001) is new for the
platform and does not collide with anything else in that list.

A Service's in-cluster `port` is the container's own listening port (for
example Keycloak answers in-cluster on 8080, not its host port 8180); the
`nodePort` and the host-side mapping are separate numbers layered on top. See
`lib/endpoints.sh` for the single source of truth this table is generated
from, and `base/config.yaml` for the env var names that resolve the
in-cluster DNS names a chapter app reads.

## Images and pins

| Component | Pin |
|---|---|
| minikube | v1.39.0, `--kubernetes-version=v1.37.2`, docker driver, containerd runtime |
| CloudNativePG | operator v1.30.1, operand `ghcr.io/cloudnative-pg/postgresql:18.6-202611090812-standard-trixie` |
| Strimzi | operator 1.3.1, Kafka 4.3.2, KRaft only, one dual-role node |
| otel-lgtm | `docker.io/grafana/otel-lgtm:0.41.0` (Grafana, Loki, Tempo, Prometheus and an OTLP receiver in one image) |
| Keycloak | `quay.io/keycloak/keycloak:26.8.2`, imports `examples/_infra/keycloak/realm-apa.json` |
| Apicurio | `quay.io/apicurio/apicurio-registry:3.3.4`, in-memory storage |
| Istio | 1.32.0, installed from the sha256-checked release tarball, STRICT mTLS |
| Kiali | 2.35.0, operator plus a `Kiali` custom resource |
| Ollama | `docker.io/ollama/ollama:0.40.16` (digest-pinned), model `qwen3:4b-instruct-2507-q4_K_M` pre-pulled onto a PVC by a plain Job |

Every pin matches `_plans/versions.md`; see that file for the survey dates
and sources.

## Loading chapter app images

```bash
./load-images.sh                # build and load every examples/NN-slug/Containerfile found
./load-images.sh 17-event-triggered-agent-wakeup   # one or more named chapters
```

The build context is `examples/`, the Maven reactor root, so
`examples/domain-model` resolves as a reactor module, matching every
chapter's own Containerfile convention. Every image loads as
`localhost/apa/<slug>:<version>` (never a bare, unqualified name and never
`latest`), matching each example's own Maven `<version>`; `overlays/minikube/apps/*`
Deployments reference that exact name with `imagePullPolicy: IfNotPresent`, so
the kubelet never attempts an external pull.

## Applying the overlay

```bash
kubectl apply -k overlays/minikube
```

`bootstrap.sh` already applies this at the end of a full run; running it by
hand is for after a manual change to one of the platform Services. A chapter
app's own Deployment and Service live under `overlays/minikube/apps/<slug>/`,
each its own kustomization (never folded into the shared platform overlay),
so a chapter's `verify.sh` can delete and reapply its own app without
touching the namespace, the ConfigMap or any other chapter's app.

## Static validation

Everything here is checked without a cluster:

```bash
shellcheck --severity=warning *.sh lib/*.sh
for f in *.sh lib/*.sh; do bash -n "$f"; done
kubectl kustomize base
kubectl kustomize overlays/minikube
kubectl kustomize overlays/minikube/apps/17-event-triggered-agent-wakeup
python3 ../../scripts/validate.py
python3 ../../scripts/host_ids.py check
../../scripts/voice-scan.sh
```

Bringing the profile up, running `bootstrap.sh` live and confirming every
tier reaches Ready happens in a separate, cluster-owning pass; nothing in
this directory was applied against a running cluster to produce this README.

## Teardown

```bash
./teardown.sh                   # delete the whole minikube profile
./teardown.sh --namespaces-only  # delete just the namespaces, keep the profile
```

Deleting the profile frees every host port in the table above.
