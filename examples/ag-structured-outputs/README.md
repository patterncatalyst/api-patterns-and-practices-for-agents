# Appendix G: structured outputs, typed consistent agent I/O

Two `quarkus-langchain4j-ollama` AiServices, `TypedIntentExtractor` and `UntypedIntentExtractor`,
share one unnamed Ollama chat model (`qwen3:4b-instruct-2507-q4_K_M`, temperature 0) and the exact
same system message, word for word (`ExtractionPrompt.SYSTEM_MESSAGE`), which states the extraction
task only and never mentions JSON, a field name or an example response. The only thing that differs
between them is each method's declared return type: `TypedIntentExtractor.extract` returns
`ExtractedIntent` (`orderId`, `intent`, `amount`); `UntypedIntentExtractor.extract` returns a bare
`String`. `quarkus-langchain4j-ollama` 1.13.4 always advertises the `RESPONSE_FORMAT_JSON_SCHEMA`
model capability (confirmed against the resolved jar's `OllamaRecorder`), so langchain4j's
AiServices machinery derives a JSON Schema from `ExtractedIntent` and attaches it as that one
request's Ollama `format`, every time `TypedIntentExtractor.extract` is called; a call to
`UntypedIntentExtractor.extract` never carries one. Ollama enforces that schema at the token level
through llama.cpp's grammar-constrained decoding: the model cannot emit a token sequence that does
not already match the shape.

`TypedIntentExtractor.extract` additionally carries `@OutputGuardrails(IntentJsonGuardrail.class)`,
a second, weaker safety net behind the grammar: a retry-on-parse-failure loop
(`quarkus.langchain4j.guardrails.max-retries`, real default 3) for the rare response the grammar
still lets through malformed. `IntentJsonGuardrail` is a thin CDI-bean subclass of
`dev.langchain4j.guardrail.JsonExtractorOutputGuardrail<ExtractedIntent>`, needed because the
library class has no no-arg constructor for Quarkus's AiServices validator to find a bean for
(confirmed live: referencing the library class directly fails the build with "Missing guardrail
bean"). A hard failure after retries are exhausted throws
`dev.langchain4j.guardrail.OutputGuardrailException`. Exactly one output guardrail is attached:
chaining more than one JSON guardrail on this book's pinned langchain4j core line (~1.19.x, ahead of
the 1.20.2 fix) is a known way to silently disable the retry.

## The two endpoints

`IntentExtractionResource` exposes both paths over the one extraction task:

- `POST /extract/typed`: calls `TypedIntentExtractor` and returns `ExtractionResponse` with
  `parseOk=true` and the parsed `ExtractedIntent` on success; returns HTTP 422 the moment that
  guarantee does not hold (an exhausted guardrail, or the schema constraint turned off, see below).
- `POST /extract/untyped`: calls `UntypedIntentExtractor`, then runs the same strict Jackson parse
  (`FAIL_ON_UNKNOWN_PROPERTIES`, default strict enum matching) against whatever text comes back, and
  always answers HTTP 200 with `parseOk` reporting whether that parse happened to succeed this
  time. It is a diagnostic, not a guarantee: the absence of a schema is not itself a failure.

Both responses share one shape, `ExtractionResponse(path, structuredOutputEnabled, parseOk, parsed,
raw)`, so a caller can tell which mechanism produced a given answer without reading two schemas.

## The negative witness

`agent.extraction.structured-output-enabled` (default `true`) is the schema constraint's own
switch. Set to `false`, `/extract/typed` calls `UntypedIntentExtractor` instead, the exact same
unconstrained path `/extract/untyped` always uses, and runs the same strict parse. The one endpoint
that otherwise guarantees a parseable `ExtractedIntent` on every call now reports `parseOk=false`
some of the time, with nothing else about the request changed: same model, same system message,
same prompt. That is the demonstration that the schema itself, not the wording asking for an
answer, was the guarantee.

## MODEL=off: the fixture lane

`agent.extraction.model-source` picks the decision path. `ollama` (the default, and what compose
and the live `verify.sh` always use) calls the real model through both AiServices. `fixture`
(forced by the `%test` profile, so `mvn -B verify` and CI never need Ollama) routes to
`FixtureIntentSource`, which replays, rather than derives live, what each path's own shape
guarantee looks like: `typedRaw` is clean JSON every time; `untypedRaw` is the same information
wrapped in markdown fences with a one-line preamble, the way a small instruct model commonly wraps
an answer it was never grammar-constrained to format, which the strict parse rejects. Both are
fixed strings so the downstream-parse wiring is proved deterministically; the live
shape-versus-drift contrast across repeated calls to the real model is `verify.sh`'s job.

## Run

```bash
../../scripts/ollama-model.sh check   # or pull, the first time
./verify.sh
```

`verify.sh` needs Docker Engine with the compose plugin; it starts Kafka, PostgreSQL, Apicurio,
Keycloak, Ollama and LGTM plus this service, sends the same extraction prompt 5 times to
`/extract/typed` and 5 times to `/extract/untyped`, asserts the typed path parses into
`ExtractedIntent` with `intent` in the allowed set on all 5 runs and the untyped path fails that
same parse on at least one of its 5, then runs the negative witness (recreating the `app` service
with `AGENT_EXTRACTION_STRUCTURED_OUTPUT_ENABLED=false`, then restoring it), and removes everything
with `down -v`. Set `KEEP=1` to leave the stack running for inspection. It ends at 11/11; see
`_plans/verification-log.md` for the recorded run.

Run the tests alone (no compose stack, no Ollama needed, since the `%test` profile forces the
fixture decision path):

```bash
mvn -B verify
```

## Tests and checks in detail

`IntentExtractionResourceTest` (`mvn -B verify`) exercises both endpoints against
`FixtureIntentSource`: the typed path parses cleanly, the untyped path reports the fixture drift it
replays, and the health endpoint reports up. `StructuredOutputDisabledTest` runs under a
`QuarkusTestProfile` that overrides `agent.extraction.structured-output-enabled=false`: the same
`/extract/typed` endpoint now returns 422, proving the in-process wiring for the negative witness
before `verify.sh` ever drives the live, container-level version of it.

`verify.sh` adds the live, real-model view: the same prompt repeated against the real
`TypedIntentExtractor` and `UntypedIntentExtractor` over the network, and the negative witness,
container-level, with the schema constraint actually disabled and restored.

## Layout

| Path | Purpose |
|------|---------|
| `src/main/java/.../Intent.java` | the closed-vocabulary enum every parsed intent must land in |
| `src/main/java/.../ExtractedIntent.java` | the typed boundary: order id, intent, amount, validated in a compact constructor |
| `src/main/java/.../ExtractionPrompt.java` | the one system message shared by both AiServices |
| `src/main/java/.../TypedIntentExtractor.java` | the TYPED path: `ExtractedIntent` return, one output guardrail |
| `src/main/java/.../IntentJsonGuardrail.java` | the CDI-bean guardrail `TypedIntentExtractor` references |
| `src/main/java/.../UntypedIntentExtractor.java` | the UNTYPED path: bare `String` return, no schema |
| `src/main/java/.../FixtureIntentSource.java` | the MODEL=off decision path: replays each path's own shape guarantee |
| `src/main/java/.../IntentExtractionBean.java` | routes `ollama`/`fixture` and the structured-output-enabled switch; the one strict parse |
| `src/main/java/.../IntentExtractionResource.java` | `POST /extract/typed`, `POST /extract/untyped` |
| `compose.yaml` | includes the shared infrastructure and adds this service |
| `Containerfile` | UBI 10 multi-stage build; context is `examples/` |
| `verify.sh` | the checks above |
