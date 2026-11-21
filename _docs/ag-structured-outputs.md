---
title: "Structured outputs: typed, consistent agent I/O"
order: 45
part: "Appendices"
description: "Why chasing byte-identical model output is the wrong contract, and how Ollama's grammar-constrained decoding types the one boundary the catalog had not typed yet: the model's own generation."
duration: "20 minutes"
---

When the same customer message goes to the same model twice, an engineer who expects the same
answer both times is asking for the wrong thing. A large language model is non-deterministic by
construction: batch-invariant kernels are not the server's default under load, floating-point
addition is not associative across GPUs and CPUs, and the model backing an endpoint can be swapped
out from under a caller an hour after the last test ran. Chasing byte-identical reproduction, a
fixed seed plus temperature zero, is not a reliable contract against any of that. What a system can
actually engineer is consistency: the same intent yields the same output shape and the same
semantic class on every call, guaranteed at a typed boundary, whatever the token-level variance
underneath looked like that run.

{% include excalidraw.html file="ag-structured-outputs" alt="Five levers from strongest to weakest: typing the output through Ollama's format grammar, typing the tool surface, validating and repairing a produced output, constraining the input, and seed/temperature as variance reduction only" caption="Figure G.1: The lever map for typed, consistent agent I/O" %}

## The lever map

A system can push toward consistent output at five different points, and they are not
interchangeable:

1. **Type the output.** Ollama's `format` parameter accepts a full JSON Schema object, enforced
   through llama.cpp's grammar-constrained decoding: the model cannot emit a token sequence that
   does not already match the schema's shape. This is the strongest lever, because it acts on
   generation itself, not on whatever came out of it.
2. **Type the tool surface.** Capture a tool's own structured result and write the durable record
   from that, never from the model's prose. The catalog already has this one: the
   captured-tool-result-not-prose pattern, where the record is read back out of what a tool
   actually returned, so a nondeterministic model cannot change what gets written as long as it
   makes the call.
3. **Validate and repair.** Check a produced output against a fixed rule after the fact, deterministic
   decision-rule review and deterministic eval replay both do this. It catches a wrong decision; it
   never prevents the model from emitting one in the first place, which is why it sits below both
   typing levers.
4. **Constrain the input.** A JSON-schema ingest gate rejects a malformed request before it ever
   reaches a model or a service. It narrows what reaches the system, not what the system can answer
   once a request is accepted.
5. **Seed and temperature.** Lowering temperature narrows which tokens are likely at each step. It
   never bounds which shape the output can take. A temperature of zero is variance reduction, not a
   consistency guarantee, and the gap between those two is the whole reason the first lever exists.

## Typing the model's own generation

Ollama has accepted a full JSON Schema object as its `format` parameter since version 0.5.0; the
pin this catalog runs, 0.40.16, carries that support against `qwen3:4b-instruct-2507-q4_K_M`, an
instruct-tuned, tool-calling-capable model with no hybrid-thinking preamble to work around.
`quarkus-langchain4j-ollama` derives a JSON Schema from an AiService method's declared return
type and sends it as that one request's Ollama `format`, automatically, whenever the return type is
a record or POJO rather than `String`, `Result`, or a streaming type, and the underlying chat model
declares the `RESPONSE_FORMAT_JSON_SCHEMA` capability, confirmed live against the resolved
`quarkus-langchain4j-ollama` 1.13.4 jar: Ollama's integration always declares that capability. Two
AiServices against the same unnamed Ollama chat model, same model id, same temperature of zero, same
system message, differ only in one method's declared return type: a bare `String` gets no schema at
all; a typed record gets one derived from its own shape.

The schema itself is not injected into the prompt text. The system message states the extraction
task in plain language and never mentions a field name, a format, or an example response; the
constraint arrives purely through `format`, applied at decode time. A live run confirms the result:
the same message, sent five times to the method with a typed return, parses into the target record
with the intent field inside its five-value enum on every run; the same message, sent five times to
the method with a `String` return, came back as a short, readable bullet list, never once valid JSON,
on all five. Turning the typed method's own schema constraint off with a single flag reproduces that
exact same bullet-list failure on the typed endpoint, and restoring the flag returns it to parsing
cleanly again: the schema, not the wording of the prompt, was the guarantee.

One sharp edge sits underneath that guarantee. The JSON Schema langchain4j derives from a Java
record does not mark any field required by default; a field is required only if its reflected
property carries a Jackson `@JsonProperty(required = true)` annotation. Left unannotated, Ollama's
grammar enforces that a present field matches its declared type, never that the field is present at
all, so a terse but fully grammar-valid response can still omit a required part of the answer. The
fix is to annotate every record component that must appear. A second, weaker safety net sits behind
the schema itself: an output guardrail that retries a parse failure up to a configured limit before
giving up and raising a hard error, useful for the rare response the grammar still lets through
malformed, never a substitute for the schema doing its job in the first place.

## What the catalog already types

Four boundaries in this catalog already apply one of the weaker levers above, so that a caller gets
a fixed shape or a fixed set of outcomes without reading the model's own words to find out which:
the captured-tool-result-not-prose pattern reads a durable record out of a tool's own structured
result; the deterministic decision-rule review checks a produced decision against a fixed rule
rather than trusting a model's account of why it decided something; the deterministic eval replay
re-runs a fixed set of scenarios against a fixed rubric so a model upgrade cannot quietly change
what counts as passing; the JSON-schema ingest gate rejects a malformed inbound request before a
service ever acts on it. None of these touches the one boundary this appendix adds: the model's own
act of generating the response. Typing that boundary, with a schema the model cannot violate at
decode time, is the strongest lever in the map precisely because every other lever only ever reacts
to text that already exists.

## Build, run, observe

```bash
./verify.sh
```

A fresh stack comes up with this service and a local Ollama serving the pinned model, then the same extraction prompt runs five times down each path. The typed path parses into `ExtractedIntent` with its intent in the allowed set on every run, shape-stable because the schema constrains decoding; the untyped path, given the identical system message, drifts and fails the same strict parse on at least one run. The negative witness turns the schema constraint off and watches `/extract/typed` start reporting `parseOk=false`, then restores it and parses cleanly again, so the schema is demonstrably what provides the guarantee. It ends at 11/11.

The test suite runs without Ollama. `mvn -B verify` passes 4/4 against a fixture source (`agent.extraction.model-source=fixture`), so CI exercises both endpoints and the disabled-schema path with no model inference; the live `./verify.sh` run proves the behaviour against a real model.

Try:

- Send a message that states the order id and amount in words rather than digits, and compare the two paths: the typed response keeps the exact record shape while the untyped text rewords itself from run to run.
- Set `agent.extraction.structured-output-enabled=false`, call `/extract/typed`, and watch the same endpoint return the untyped path's unparseable prose; restore the flag and it parses again.
- Remove `@JsonProperty(required = true)` from one component of `ExtractedIntent`, rebuild, and watch the grammar stop enforcing that field's presence: the model may omit it, because the derived schema now constrains type-if-present rather than presence.

## References

- Ollama, *Structured outputs*: [ollama.com/blog/structured-outputs](https://ollama.com/blog/structured-outputs)
- LangChain4j, *Structured outputs*: [docs.langchain4j.dev/tutorials/structured-outputs](https://docs.langchain4j.dev/tutorials/structured-outputs/)
- Thinking Machines Lab, *Defeating nondeterminism in LLM inference*: [thinkingmachines.ai/blog/defeating-nondeterminism-in-llm-inference](https://thinkingmachines.ai/blog/defeating-nondeterminism-in-llm-inference/)
- llama.cpp, *Grammars*: [github.com/ggml-org/llama.cpp/blob/master/grammars/README.md](https://github.com/ggml-org/llama.cpp/blob/master/grammars/README.md)
