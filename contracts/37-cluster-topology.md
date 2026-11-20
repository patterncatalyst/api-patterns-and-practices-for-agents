# Cluster topology: deploying a reused service onto the shared platform

The shared Kubernetes platform (`examples/_k8s`) publishes a fixed set of NodePorts and a fixed
in-cluster env contract. This file documents both for the reused event-triggered-agent-wakeup
service, the same role an AsyncAPI document or an OpenAPI contract plays for a wire-level
pattern; here the contract is the cluster's own endpoint and namespace shape.

## Namespaces

| Namespace | Holds | Mesh |
|---|---|---|
| `apa` | the deployed service's Deployment and Service, the shared `apa-app-config` ConfigMap | Istio sidecar injection, STRICT mTLS |
| `apa-data` | the Strimzi Kafka cluster, the CloudNativePG Postgres cluster, Ollama | not meshed |
| `observability` | the single `otel-lgtm` Deployment | not meshed |

## In-cluster DNS (the env contract the Deployment reads)

| Variable | Value | Reached by |
|---|---|---|
| `CAMEL_COMPONENT_KAFKA_BROKERS` | `apa-kafka-bootstrap.apa-data.svc.cluster.local:9092` | the Camel Kafka consumer route |
| `QUARKUS_DATASOURCE_JDBC_URL` | `jdbc:postgresql://apa-postgres-rw.apa-data.svc.cluster.local:5432/apa` | Flyway and the durable write |
| `QUARKUS_LANGCHAIN4J_OLLAMA_BASE_URL` | `http://ollama.apa-data.svc.cluster.local:11434` | the langchain4j AiService |
| `QUARKUS_OTEL_EXPORTER_OTLP_ENDPOINT` | `http://otel-lgtm.observability.svc.cluster.local:4318` | traces, metrics and logs |

## Host NodePorts (the only host access; never a forward)

| Endpoint | Host port | Used by `verify.sh` for |
|---|---|---|
| Kafka (Strimzi external listener) | 127.0.0.1:9092 | `kcat` producing the trigger event |
| PostgreSQL | 127.0.0.1:5432 | published for inspection; `verify.sh` itself reads the durable row through `kubectl exec ... psql` against the CloudNativePG pod instead |
| Tempo | 127.0.0.1:3200 | asserting a trace for the deployed service |
| Prometheus | 127.0.0.1:19090 | asserting the tool-call counter |
| Loki | 127.0.0.1:3100 | asserting the tool's own log line |
| Grafana | 127.0.0.1:3000 | manual inspection with `KEEP=1` |

## The deployed workload

| Field | Value |
|---|---|
| Deployment and Service name | `event-triggered-agent-wakeup` |
| Namespace | `apa` |
| Image | `localhost/apa/17-event-triggered-agent-wakeup:1.0.0`, loaded by `../_k8s/load-images.sh`, never pulled |
| Kustomization | `examples/_k8s/overlays/minikube/apps/17-event-triggered-agent-wakeup/`, applied and deleted on its own, independent of the shared platform overlay |
| Consumer-enable flag | `WAKEUP_CONSUMER_ENABLED` on the Deployment's own env, flipped with `kubectl set env` for the negative witness |

## Assertion paths

| What | How | Never |
|---|---|---|
| The trigger event | `kcat -P` against the Kafka NodePort | a forwarded or proxied connection |
| The durable effect | `kubectl exec <CloudNativePG pod> -n apa-data -- psql`, carrier read back as FASTLINE or AIRCO | the model's own prose |
| Traces, metrics, logs | `curl` against Tempo, Prometheus and Loki's own NodePorts | `kubectl port-forward` or a Grafana login |
| The negative witness | `kubectl set env` plus `kubectl rollout status`, never a source edit | a point-in-time metric absence (the row is the witness) |
