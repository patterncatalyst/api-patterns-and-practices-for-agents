---
layout: plan
title: "r16 plan (ch38 hands-on exercise guide)"
render_with_liquid: false
output: false
---

# r16 Implementation Plan: ch38 hands-on "Exercise the mesh yourself"

Status: APPROVED (owner 2026-11-21: all five areas; dedicated step-by-step section). A content enhancement to the final-project chapter so the reader EXERCISES the mesh, not just runs `verify.sh`. No new runtime behaviour; `verify.sh` stays the automated proof. (Decks move to r17; whole-book workshop is a later follow-up, see [[project_api_ch38_handson_workshop]].)

## What changes
Add a new `## Exercise the mesh yourself` section to `_docs/38-final-project-data-mesh.md` (a non-pattern project chapter; the new heading goes AFTER `## Build, run, observe` and BEFORE `## Anti-patterns`, which `validate.py check_chapter_spine` allows). Numbered steps, each: the exact command the reader runs against the live minikube mesh, what to observe, and why it matters. Derived FAITHFULLY from the already-verified `examples/38-final-project-data-mesh/verify.sh` (23/23 live, r12b) and `examples/_k8s/lib/endpoints.sh`, but written for a human running them interactively (show the MCP Streamable-HTTP initialize + session handshake that verify.sh hides in a helper).

## The five areas (owner-approved), each a step
1. Domain ownership: `kubectl exec` psql into the CNPG primary and show each product owns its own database (`orders`, `payments`, `shipping`, `shipping_track`); a row in one is invisible to another.
2. Data as a product: call each output port over its NodePort by hand (orders `/mcp` orderStatus, payments facade `/mcp` takePayment, shipping-track `/mcp/shipping` shipping_track_get), including the MCP initialize/session handshake, and read the responses.
3. The agent-driven flow: take a payment through the facade, `kcat`-produce `apa.payments.processed`, then watch the shipping agent wake and write a `shipment_prep` row (carrier FASTLINE or AIRCO) observed via psql, not model prose.
4. Federated governance: register the audit schema under a BACKWARD rule in Apicurio, post a compatible version (accepted) and a breaking version (rejected 409/400), by hand against the Apicurio NodePort.
5. The mesh-hop witness: `kubectl scale` the shipping writer to 0, produce a second payment, show the paid-but-unshipped gap (no `shipment_prep` row), then scale back to 1 and watch the missed event get read.

## Constraints
- House rules: no em/en dashes; no host identifiers (use `127.0.0.1:<port>`, `<repo>/`); "final project" never "capstone"; the chapter stands alone (no cross-chapter refs); versions only on setup surfaces. The new section may be long but each step stays tight (command, observation, one-line why).
- Every command must match what `verify.sh` already proves; do not invent commands or endpoints. NodePorts from `examples/_k8s/lib/endpoints.sh` (orders 8088, payments 8089, shipping-track 8090, Kafka 9092, Postgres via `kubectl exec`, Apicurio 8081).
- Keep `verify.sh` unchanged (it is the automated proof); the hands-on section is the guided path.

## Verification (owner decision, see Open question)
The commands are proven by `verify.sh`'s 23/23 live run (r12b). The new risk is only the reader-interactive path (the MCP handshake done by hand). Options: (a) live-verify the exact reader steps once by bringing the `apa` minikube mesh back up (~30-40 min, gated); (b) ship derived-from-verified, relying on verify.sh's proof. RECOMMEND (a) for the book's verify-while-writing discipline.

## Waves
- Wave A (Sonnet): author the `## Exercise the mesh yourself` section, derived from verify.sh + endpoints.sh; static gates (validate, voice, host_ids, site-build).
- Wave B (Opus): validate the section against verify.sh for command accuracy + voice + spine.
- Wave C (orchestrator, owner-gated): live-verify the reader steps on the `apa` minikube mesh, OR ship derived-from-verified per the owner's call.
- Ship r16.0.

## Open question for the owner
Live-verify the hands-on steps on a fresh minikube bring-up (RECOMMEND), or ship them as faithfully derived from the already-verified `verify.sh`?
