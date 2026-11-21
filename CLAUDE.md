# CLAUDE.md

## Project overview

A chaptered pattern catalog, in the style of Enterprise Integration Patterns,
for building APIs that AI agents and large language models consume: REST and
GraphQL reshaped as MCP tools, event-driven patterns for waking agents up on
Kafka, and the identity, traffic and observability guardrails a
production agent-facing surface needs. The vehicle is Quarkus, Apache Camel
and Kafka; `quarkus-mcp-server` packages the tool surface; Keycloak is the
book-wide identity provider; langchain4j (via `quarkus-langchain4j`) and
Ollama drive the consuming side. Every pattern chapter states a stack-neutral
contract first, so the same idea transfers to Python/FastAPI and Strawberry
GraphQL; no Python/FastAPI tab ships in this book itself. Jekyll site plus one
runnable example per chapter. Licences: code Apache-2.0, prose CC BY 4.0.

This is r01: the site, tooling, CI and plan skeleton, with no chapters and no
runnable examples. The approved iteration plan and the full Part-to-chapter
outline are in `_plans/iteration-plan.md`.

## Publishing rule: nothing identifies the machine or its owner

The repo is public, so no tracked file may name the development machine, its
owner or anything that helps locate or attack it later:

- No absolute home-directory path (under /home or /Users), no account name, no hostname. Write `<repo>/...` or `~/...`.
- No laptop model, no CPU model or model number. The CPU is a class ("8-core/16-thread laptop CPU"); prose names its properties, never the part.
- No host kernel build number or build date: give `major.minor` and the preempt model.
- No `/etc/machine-id`, DMI serial or UUID, MAC or IP address, `root=UUID=` or BOOT_IMAGE.
- Generated output is scrubbed before it is committed: `host_ids.py scrub <dir>` on a results directory, `host_ids.py filter` on a captured log (run inside the repo, or pass `--repo`, so the repo path becomes `<repo>`).
- `host_ids.py check` runs in CI and fails the build on a hit. Run it before every commit; planners and logs are the usual leak.

## House rules

- **Versions**: exact pins, never prerelease, `latest` or floating tags. A component is pinned to its latest stable release once, when it is introduced; an existing pin moves only for a valid reason (a security fix, an actual incompatibility, or a feature a chapter needs), recorded next to the pin in `_plans/versions.md`. Open source only; nothing needing a licence key, subscription or fee. JDK 25 minimum.
- **Images**: every image the book builds starts from UBI 10 (`registry.access.redhat.com/ubi10/openjdk-25`, `-runtime`). Fall back to `ubi9/openjdk-25` (same runtime, never a lower JDK) only on a real incompatibility, recorded next to the pin. Upstream images are used as published (Keycloak, Apicurio, Strimzi, LGTM, Ollama).
- **Idiomatic code**: `quarkus-mcp-server` for MCP packaging, Quarkus REST and GraphQL, `quarkus-langchain4j` for the consuming side, SmallRye Reactive Messaging, SmallRye Health, Micrometer and OpenTelemetry, Dev Services, Flyway; Camel Java DSL, one route per `RouteBuilder`. Nothing hand-rolled that the framework provides.
- **No vendor platforms in code**: nothing depends on OpenShift, CRC or a cloud. Appendix B is a demonstration of the same neutral code.
- **Fixed domain**: the order-to-delivery shipping domain (services order, inventory, payment, shipping, notification), mirroring the event-driven-patterns-with-quarkus-and-camel sibling. Use only the entity, event and topic names in `_plans/domain-model.md`; never invent an entity or an area. Carriers are FASTLINE and AIRCO, never a real carrier. Event payload records live per example, not in domain-model.
- **Evidence**: every claim in prose is backed by the example's tests, a recorded run or a fetched source. Evidence is never written into the prose; `_plans/claims.md` tracks it.
- **Credit sources**: cite every source by author and work in `bibliography.md`. Never copy text, code or examples.
- **Comparison code** is allowed as the exception (for example a Python/FastAPI cross-stack build run as a reviewer check), tested on the same input; the idiomatic Quarkus/Camel code is the lesson.
- **Fix now, do not defer**: defects and follow-ups are fixed in the current iteration and re-measured. Defer only content that belongs to a later part.
- **No host changes**: no sudo, packages, sysctl, firewall or kernel changes on the shared host.
- **Terminology**: Part 7's closing project is the **final project**, never "capstone".

## Stack rules

- Docker Engine and `docker compose` only (never Docker Desktop, never podman outside the OpenShift Local appendix). Fedora/RHEL hosts.
- One stack or cluster at a time. Stop it when idle.
- **No forwarding of any kind**: no `kubectl port-forward`, `minikube tunnel`, SSH tunnels (`ssh -L/-R/-D`), `socat` relays, IP forwarding, iptables/nft/firewalld rules or NAT. Publish ports instead: compose `ports:`, and on minikube NodePorts published with `minikube start --ports`. `scripts/validate.py` fails the build on any of these in reader-facing content, examples, scripts or workflows.
- Host ports: Kafka 9092, PostgreSQL 5432, Apicurio 8081 (management and health 19000), Keycloak 8180, Ollama 11434, Grafana 3000, OTLP 4317/4318, Tempo 3200, Loki 3100, Prometheus 19090 (host 9090 is commonly taken by Cockpit on Fedora/RHEL).
- The compose stack (`examples/_infra/compose.yaml`): Kafka KRaft, PostgreSQL on the CloudNativePG operand image, Apicurio Registry, **Keycloak as the book-wide identity provider**, Ollama. Grafana LGTM is a separate compose file (`compose.lgtm.yaml`), started independently so observability can come up or go down without disturbing the application stack.
- The final project (Part 7) runs on Kubernetes with Strimzi.
- OpenShift Local (CRC) appears only in Appendix B, running the final project's data products.
- Kafka 4.3.2 in every run target (held to what the version in use supports); KRaft only, no ZooKeeper.
- `quarkus-mcp-server` serves both the HTTP (Streamable HTTP) and stdio transports; stdio is how a chapter's example is consumed from Claude Code in Part 4.

## Structure

```
_docs/            chapters and appendices (Jekyll docs collection; empty until r02)
_parts/           part cards
_plans/           iteration plan, domain mirror, version pins, claims, verification log (output: false)
_layouts/ _includes/ assets/   site machinery
assets/diagrams/  <name>.svg + <name>.excalidraw pairs, specs/<name>.py sources
contracts/        OpenAPI, GraphQL SDL, MCP manifests, Avro/JSON schemas as they land
examples/_infra/  shared compose stack (Kafka, PostgreSQL, Apicurio, Keycloak, Ollama); LGTM separate
examples/_k8s/    Kubernetes manifests for the final project (Part 7)
examples/domain-model/   shared order-to-delivery records (module lands r02), package com.patterncatalyst.agentapi
examples/NN-slug/ one Quarkus + Camel project, compose include, Containerfile, verify.sh, README
scripts/          validate.py, voice-scan.sh, idiomatic-scan.sh, host_ids.py, site-build.sh,
                  generate_diagram.py, build_diagrams_page.py, build-diagrams.sh, verify-all.sh
bibliography.md  LICENSE  LICENSE-docs.md
```

## Chapter spine and writing rules

Front matter: title, order, part, description, duration. `part` must match a `_parts` file's `part_name`. Quote values that contain colons.

- Write like Enterprise Integration Patterns, Geewax tone: problem before mechanism, show the code, state the trade-off, stop.
- Chapter length follows the information, not a word count, in the Enterprise Integration Patterns style: cover what the pattern needs, show the code, state the trade-off, stop. There is no hard floor and no hard ceiling. Never pad to reach a number, and never cut substance to stay under one; add a diagram where density climbs.
- **Every non-appendix chapter opens with the same two headings, in order**: `## Learning objectives` first (a bulleted list of what the reader walks away able to do) and `## Why this matters` second (the motivating problem, before any mechanism).
- Pattern chapters keep the pattern spine after that: `## Pattern: <Name>` with `### The problem`, `### The solution` (one bold one-sentence definition), `### How Quarkus and Camel model it` (or `### How Quarkus models it` in a chapter whose example has no Camel); then `## Build, run, observe` (short: one code block with the commands, then one or two sentences on what runs and what to watch, ending with the pass count, then at most three `Try` bullets of one sentence each naming the switch and the visible effect; 250 words as a soft cap; no test lists, no check numbers, no run statistics, which belong in the example's README).
- **Every non-appendix chapter carries `## Anti-patterns` before `## References`**: the common misuses and their visible symptom. It is a distinct heading from `## Build, run, observe` and always comes after it. Pattern chapters keep `## When not to use it` between `## Anti-patterns` and `## References`; non-pattern chapters (00 to 02) go straight from `## Anti-patterns` to `## References`. Appendices are exempt until they land; they are unbuilt and decided later. `scripts/validate.py` (`check_chapter_spine`) enforces the full spine: the required heading set, the Learning-objectives-first/Why-this-matters-second position, the bulleted Learning objectives, and the order through Anti-patterns, When not to use it and References.
- **Exactly one cross-stack AI callout per pattern chapter**, in this form, with no HTML wrapper and no nested quote:

  ```
  > **🤖 Explore with your agent**
  > **Intent (stack-neutral):** one sentence: what the API must afford the agent.
  > **Contract shape:** operation intent, inputs, response shape, error shape, idempotency key, stated without a framework.
  > **Build it here:** "<runnable prompt naming this example's own types and the chapter's decision rule>"
  > **Build it elsewhere:** "Implement the same pattern in Python/FastAPI (and Strawberry GraphQL): <prompt restating the stack-neutral contract>"
  > Check: <one observable assertion that holds regardless of stack>
  ```

  `scripts/validate.py` enforces the shape: the exact heading, the four required labelled lines, exactly one `Check:` line, and no nested quote.
- At least one figure per chapter, from a spec. More than one is welcome wherever a figure explains a flow better than text. Every figure: `assets/diagrams/specs/<name>.py` via the diagram generator, output `assets/diagrams/<name>.svg` and `.excalidraw`. Never hand-edit outputs.
- No em dashes or en dashes anywhere reader-facing (chapters, READMEs, example pages, figures, deck). Use commas, colons, parentheses or "to".
- No self-reference ("this chapter", "the example shows"), no test names in prose, no line-by-line code narration. State the behaviour.
- Versions appear only on setup surfaces (chapter 00, the appendices, and a chapter's own "Setting up the cluster" section), except where the version is the feature.
- **Each chapter stands alone**: no reference to another chapter, appendix or part of this book anywhere in the prose, back or forward, and no links to other pages in the prose. A page states what it needs in its own words; related pages are linked only under `## References`. Appendices are reference material that map, translate or build on the chapters, so they may name them: `validate.py` exempts every appendix page (`a*-*.md`) from this rule, not only Appendix D. Chapter numbers of other books cited as sources are allowed too. `validate.py` enforces the rule on the chapter pages. Example `README.md` files are developer-facing repository docs, not reader-facing chapter pages, and may name the sibling services they reuse. No per-chapter verification footer; run records live in `_plans/verification-log.md`.
- Part numbering is Arabic (Part 0, Part 1, ...), never Roman numerals, matching the `_parts` `order` field.
- Companion decks are the final deliverable (r16-equivalent): write every chapter and build every example first, then mine the deck from finished content.

## Example contract

- One Quarkus + Camel project per chapter, the smallest service set that makes the pattern visible: a few classes, one or two routes, one topic or table per idea. A reviewer reads the whole example in one sitting.
- `verify.sh` checks the observable effect, not an exit code, and includes a negative witness: a check that fails when the pattern's guard is removed.
- Traces, metrics and logs reach LGTM through OpenTelemetry from the first example that touches the network.
- Runs alone from a clean stack, with no other example running.
- Every live run starts fresh: `verify.sh` first runs `docker compose down -v --remove-orphans`, then `up -d --build --force-recreate --renew-anon-volumes --wait`, and ends with `down -v` unless `KEEP=1`. Containers and volumes are never reused between runs. `scripts/validate.py` enforces it.
- Quarkus tests use Dev Services and run before compose.
- Topic convention: **`apa.<domain>.<event>`**, dots and hyphens only (for example `apa.orders.placed`).
- Shared domain records come from `examples/domain-model`; no per-example copies. `mvn -B verify` in an example requires `examples/domain-model` installed first (`mvn -B -q install` there).
- Every app service in compose has a healthcheck, so `up --wait` covers it.
- Containerfiles follow the Quarkus `Dockerfile.jvm` convention: the build stage runs as user 185 with `mvn -B -ntp`; the runtime stage copies the four fast-jar layers (`lib/`, `*.jar`, `app/`, `quarkus/`) with `--chown=185` and uses the image's `run-java` default entrypoint with `JAVA_APP_JAR=/deployments/quarkus-run.jar`.

## Validate before commit

```bash
python3 scripts/validate.py --self-test
python3 scripts/validate.py
scripts/voice-scan.sh
scripts/idiomatic-scan.sh
python3 scripts/host_ids.py self-test
python3 scripts/host_ids.py check
scripts/site-build.sh        # Jekyll in a ruby container
```

CI runs the same checks, plus `mvn verify` per changed example once examples exist; the live `verify.sh` runs locally and is logged in `_plans/verification-log.md`.

## Git flow

- One `release/rNN` branch and one PR per iteration. Squash-merge on the owner's OK. Tag `rNN.x`; the GitHub release is notes only. Delete the branch on merge.
- Conventional Commits with scopes `§N` (chapter), `demo-NN` (example) or `rNN.x` (iteration), for example `docs(§03): intent endpoint chapter`.
- No AI-attribution trailers in commits (harness-level rule).
- Run `python3 scripts/host_ids.py check` before every commit.

## Skills

lgtm-relay (Opus plans, owner reviews, Sonnet builds, Opus validates), lgtm-tutorial, lgtm-jekyll, lgtm-quarkus, lgtm-camel, lgtm-docker-stack, lgtm-minikube-stack, lgtm-crc, lgtm-professional-voice, lgtm-idiomatic-review, lgtm-diagram-generator, lgtm-presentation, lgtm-host-scrub, lgtm-github.
