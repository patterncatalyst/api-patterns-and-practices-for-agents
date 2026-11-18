# Examples

One runnable Quarkus and Camel project per pattern chapter, built on the
shared order-to-delivery domain described in `_plans/domain-model.md`.

```
examples/
├── _infra/          shared compose stack (Kafka, PostgreSQL, Apicurio, Keycloak, Ollama)
├── _infra/compose.lgtm.yaml   Grafana LGTM, started separately
├── _k8s/            minikube manifests for the final project (Part 7)
├── domain-model/    shared order-to-delivery records (module lands r02)
└── NN-slug/         one example per chapter, with its own README.md and verify.sh
```

r01 ships the infrastructure declarations only; no example wires into them
yet, and `examples/domain-model/` is a directory and a README, not a buildable
module. The first runnable example lands in r02 alongside Part 0's chapters.

## Run the shared stack (once examples exist)

```bash
docker compose -f examples/_infra/compose.yaml up -d --wait
docker compose -f examples/_infra/compose.lgtm.yaml up -d --wait
```

## Run an example (once examples exist)

```bash
cd examples/NN-slug
./verify.sh
```

Each `verify.sh` brings its own stack up and tears it down. Running
`mvn -B verify` inside an example needs the shared domain model installed
first:

```bash
(cd examples/domain-model && mvn -B -q install)
```
