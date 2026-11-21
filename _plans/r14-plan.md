---
layout: plan
title: "r14 plan (Appendix B: OpenShift Local / CRC)"
render_with_liquid: false
output: false
---

# r14 Implementation Plan: Appendix B, running the final project's data products on OpenShift Local (CRC)

Status: APPROVED with decisions (owner 2026-11-21). The last appendix.

**LOCKED DECISIONS (owner 2026-11-21):**
1. **SPLIT r14a / r14b.** r14a = scaffold `examples/_openshift/` + offline gates (clean checkpoint). r14b = orchestrator runs the live CRC lane + the appendix chapter.
2. **Full ch38 mesh on the core tier:** all 3 products / 5 deployments + Kafka + per-product Postgres + Ollama + Apicurio, running ch38's full end-to-end scenario on OpenShift.
3. **Attempt the OSSM mesh platform tier live too** in r14b (OpenShift Service Mesh 3 + Kiali), beyond the core tier. Scripts for KEDA / RH OTel / GitOps are shipped and documented as opt-in; the mesh tier is verified live if the core tier lands (fall back to documented-not-verified if the mesh tier blocks, per the datamesh precedent, never faked).
4. **Orchestrator runs the live CRC lane** (CRC is set up, pull secret present; ~60-90 min including `crc start`).
5. Placement `examples/_openshift/` (beside `examples/_k8s`); Keycloak omitted from the core tier (not exercised by the scenario). Appendix doc `_docs/ab-openshift-local.md`, order 40. Demonstrates the SAME neutral code (ch38's data mesh) running on an on-premises OpenShift distribution, with OpenShift-native mechanisms only in a new `examples/_openshift/` layer. Uses the `lgtm-crc` skill conventions. All house gates apply; appendix doc is spine-exempt and xref-exempt.

## Environment (confirmed 2026-11-21)
CRC 2.64.0 / OpenShift 4.22.14 (exactly the `lgtm-crc` verified version), VM Stopped, bundle cached (no re-download), pull secret configured (free Red Hat registration, never printed/committed), sized 12 cpu / 32 GB / 100 GB (platform-tier sizing), on a 22-core / 62 GB host. `oc`, `helm`, `skopeo` present. minikube `apa` Stopped (one cluster at a time). Live CRC verification is feasible.

## What Appendix B runs
ch38's three agent-facing data products (5 deployments, reused unchanged except the `openshift` Maven profile + in-cluster build): orders (ch13), payments facade + gRPC backend (ch15), shipping read port (ch14) + event-woken writer (ch17). Platform deps the ch38 scenario exercises: Kafka (`apa.payments.processed`), per-product Postgres, Ollama (the shipping agent's carrier decision), Apicurio (the BACKWARD governance gate). Keycloak is present in ch38 but not exercised by the scenario (the reused apps are not OIDC-gated), so it is OPTIONAL here.

## The OpenShift-native translation (the point of the appendix)
The application code, images, env contract (ConfigMap keys, Service/Secret names) and verification semantics stay identical to minikube; only the `examples/_openshift/` layer differs:
- **minikube image load -> in-cluster S2I binary builds** (`quarkus-openshift`, UBI10 openjdk-25). Add the `openshift` Maven profile to the 5 reused modules' poms (snippet from the skill); nothing else in the poms or `application.properties` changes.
- **NodePorts -> edge-TLS Routes** for the 3 host-facing products (orders, payments-facade, shipping-track); payment-service (gRPC) and shipping-writer stay internal (ClusterIP), reached by `oc exec`.
- **Strimzi -> AMQ Streams** (Red Hat build of Strimzi), **raw CRs/helm -> OLM operators** (pinned CSV, `installPlanApproval: Manual`, only the named InstallPlan approved).
- **STRICT-mTLS-via-Istio (minikube) -> restricted-v2 SCC** on every pod (runAsNonRoot, drop ALL, seccomp RuntimeDefault, no fixed UID); the mesh is the opt-in platform tier, not the core.
- **host NodePort curl -> Route curl with the cluster ingress CA** (`curl --cacert`, never `--insecure`); internal Services via `oc exec`.

## RECOMMENDED SPLIT: r14a (scaffold + offline) then r14b (live CRC + chapter)
- **r14a**: scaffold `examples/_openshift/` from the `lgtm-crc` templates, substitute placeholders for this project, fill the Helm `services:` map (5 deployments + per-product Postgres + Routes), add the `openshift` Maven profile to the 5 poms, write `project-checks.sh` with ch38's end-to-end scenario as Route/`oc exec` checks, and pass OFFLINE gates (`bash -n` every script, `helm lint`, `helm template` with flags, validate/voice/idiomatic/host_ids). No cluster. Ships as a clean checkpoint.
- **r14b**: the live CRC lane (orchestrator) + the appendix chapter `_docs/ab-openshift-local.md` + evidence (host-scrubbed). Gated on r14a.

## Core tier vs platform tier (owner decision)
- **Core tier (the r14b verification deliverable):** AMQ Streams Kafka, per-product Postgres, in-cluster S2I builds, the Helm chart, edge-TLS Routes, Ollama (shipping agent), Apicurio (governance gate), restricted-v2. Runs ch38's end-to-end agent-driven scenario on OpenShift and asserts captured side effects via Routes + `oc exec`.
- **Platform tier (opt-in, scripts shipped, documented):** OpenShift Service Mesh 3 (OSSM, the Istio parity), Kiali, KEDA, Red Hat build of OpenTelemetry + otel-lgtm, GitOps (Argo CD). The sibling `datamesh-reference-arch-quarkus` CRC appendix found the platform tier blocked on leftovers; so RECOMMEND shipping the platform-tier scripts and documenting them as opt-in, and verifying the CORE tier live in r14b. Attempt the OSSM mesh tier live only if the core tier lands with budget to spare.

## Operators (OLM, pinned, Manual approval; survey CSVs live at r14b G0)
Survey the newest CSV in the newest stable channel on the running cluster (`oc get packagemanifest <pkg> -o jsonpath=...`) at r14b start; the `lgtm-crc` `references/versions.md` 2026-10-09 table is the starting point, re-checked live. Candidates: AMQ Streams (Kafka), Apicurio Registry operator (or a chart deploy if the operator is absent/awkward on CRC), CloudNativePG (community operator, to match ch38) OR a chart-deployed Postgres (decision at G0 by what installs cleanly under restricted-v2). Ollama: a plain Deployment (no operator), with the pinned model pre-pulled onto a PVC. Keycloak: omitted from the core tier unless the owner wants it.

## Verification (r14b, live CRC lane, orchestrator)
Run order (one cluster at a time; crc delete/start for a pristine cluster before the verify pass, per the skill): `crc start` -> `install-infra.sh` (operators + Kafka + Postgres) -> `build-images.sh` (S2I) -> `deploy.sh` (chart) -> `capture-evidence.sh`/`project-checks.sh` -> `teardown.sh` -> `crc stop`. The scenario mirrors ch38: orders read over its Route; a payment over the payments Route; `kcat` (via `oc exec`) produces `apa.payments.processed`; the shipping agent writes a durable row (via `oc exec psql`, carrier FASTLINE/AIRCO, never model prose); the Apicurio BACKWARD gate rejects an incompatible schema; the biting mesh-hop witness (scale shipping-writer to 0, no row, restore, row appears). Evidence passes the secret scrub and `host_ids.py scrub`/`filter` before any file is committed. Claims flip to Verified only on a real pass; a component that proves blocked on CRC is documented (base-verified / that-tier-blocked), never faked.

## Waves (lgtm-relay; serialize on shared files; operator installs sequential, never parallel)
- **G0 (r14a):** resolve owner questions; confirm placement and scope. Files: r14-plan.md.
- **Wave A (r14a, Sonnet):** scaffold `examples/_openshift/` + substitute + fill chart + add the 5 poms' `openshift` profile + `project-checks.sh`; offline gates. Restate the `lgtm-crc` key principles in the executor prompt (subagents do not inherit the skill).
- **Wave B (r14a):** offline static gate (bash -n, helm lint/template, validate, voice, idiomatic, host_ids, site-build); Opus validate; ship r14a.0.
- **Wave C (r14b, orchestrator, live):** `crc delete && crc start`, G0 CSV survey, then the run order above; log verification-log + claims; capture + scrub evidence.
- **Wave D (r14b):** `_docs/ab-openshift-local.md` (appendix chapter, order 40, part "Appendices") + figure; known-issues table; Opus validate; ship r14b.0.

## Acceptance criteria
- `examples/_openshift/` scaffolds and passes offline: `bash -n` all scripts, `helm lint`, `helm template` with every flag, validate/voice/idiomatic/host_ids clean. The 5 reused modules gain only the `openshift` Maven profile; their `application.properties` and env contract are byte-identical to the minikube run.
- Live (r14b): images build in-cluster via S2I; pods run under restricted-v2 (no fixed UID); the 3 host-facing products answer over edge-TLS Routes verified with the ingress CA; the ch38 end-to-end scenario and the mesh-hop negative witness pass, asserted via Routes + `oc exec`; teardown returns CRC to empty, then `crc stop`. Evidence host-scrubbed. No forwarding, no `--insecure`, no printed secrets.
- Appendix B doc passes all gates (spine-exempt), "final project" never "capstone", no em/en dashes, OpenShift-specific pieces confined to the `examples/_openshift/` layer and the appendix.
- No new application dependency; new operator/image pins recorded in versions.md with reasons and the live CSV survey date.

## Risks (+ detection)
- **Platform tier (OSSM) blocked on CRC** (datamesh precedent): DETECT at install; MITIGATE ship scripts + document as opt-in, verify core tier only.
- **Operator absent/awkward on CRC OperatorHub** (Apicurio, CNPG community): DETECT at the live CSV survey; MITIGATE chart-deployed fallback for Postgres/Apicurio, recorded.
- **Ollama resources on CRC** (4 GB + model pull): DETECT readiness; MITIGATE pre-pull onto a PVC; the model-free paths still verify if Ollama is constrained.
- **S2I build of the gRPC module** (proto codegen at build): DETECT build log; the `openshift` profile keeps the same `quarkus-maven-plugin` codegen.
- **CRC dirty from a prior project:** inventory `oc get sub,csv -A` + non-system namespaces before the run; `crc delete && crc start` for a pristine verify pass.
- **Secret/host-id leak in evidence:** the secret scrub + `host_ids.py` on every captured file before commit; CI `host_ids.py check` as the backstop.

## Open questions for the owner
1. **Split r14a (scaffold + offline) then r14b (live CRC + chapter)?** RECOMMEND SPLIT.
2. **Scope:** full ch38 mesh (5 deployments + Kafka + Postgres + Ollama + Apicurio) on the CRC core tier (RECOMMEND), or a focused subset (orders + payments + Kafka + Postgres only)?
3. **Platform tier:** ship OSSM/KEDA/OTel/GitOps scripts + document as opt-in and verify the CORE tier live (RECOMMEND, per the datamesh caution), or attempt the OSSM mesh tier live in r14b too?
4. **Who runs the live CRC lane:** the orchestrator (RECOMMEND; CRC is set up and the pull secret is present; it is a long run, roughly 60 to 90 minutes including `crc start`), or the owner?
5. **Placement:** `examples/_openshift/` beside `examples/_k8s` and `examples/_infra` (RECOMMEND), or repo-root `openshift/` (the skill default)?
6. **Keycloak** in the core tier: omit (RECOMMEND, not exercised by the scenario) or include for parity with ch38?
