---
title: "llms.txt in depth"
order: 39
part: "Appendices"
description: "A reference on the llms.txt convention: its structure, why a tool surface renders it rather than hand-writes it, and how it differs in purpose from robots.txt, sitemap.xml and OpenAPI."
duration: "15 minutes"
---

## Learning objectives

- State the problem `/llms.txt` solves: a curated, agent-readable index of a site or service, distinct from the documents it points to.
- Name the four structural pieces the convention requires and what each one is for.
- Explain why a tool-serving build renders `/llms.txt` from its own live tool registry instead of maintaining the file by hand.
- Place `/llms.txt` correctly against `robots.txt`, `sitemap.xml` and an OpenAPI document: four files with four different audiences and four different jobs.

## Why this matters

An agent arriving at an unfamiliar API has no browser chrome, no visible navigation and no habit of scanning a homepage for a "Docs" link. It has a context window, a handful of turns before the budget runs out, and a strong preference for a short, curated answer over a long one it has to filter itself. `robots.txt` was built for a different reader entirely, a crawler deciding whether it may fetch a URL at all, and a full OpenAPI document answers a question the agent has not asked yet: not "what does every parameter of every operation accept" but "what is here, and which of it matters." `/llms.txt` exists to answer that second question in a shape small enough to read in one turn.

## What llms.txt is, and the problem it solves

A human landing on a service's homepage skims a nav bar, a few headings and maybe a search box, then decides what to click. A model reading the same service has no nav bar to skim; it has whatever text a request returns. Pointing that model at the full site, every page, every doc, every generated reference, forces it to do the skimming itself, token by token, out of a budget that was never going to stretch that far.

`llms.txt` (proposed by Jeremy Howard in September 2024, at [llmstxt.org](https://llmstxt.org/)) is a plain-text, Markdown-shaped file served at a well-known path, `/llms.txt`, that does the skimming once, on the server side, and hands the result to anyone who asks. It is not a mirror of the site's content and it is not a summary of what the site does in the abstract. It is an index: a short, curated list of what exists and where to find more detail, written by whoever owns the service rather than scraped and guessed at by whoever is reading it.

The distinction that matters is curation versus completeness. A sitemap is complete by design, every URL, so a crawler misses nothing. `llms.txt` is curated by design, the pieces an agent needs, so a reader with a fixed context budget does not have to filter out everything else itself.

## The file's structure

The specification fixes four pieces, three of them optional:

1. **An H1 title.** The one line every `llms.txt` must have: the project or service's name. Nothing else in the file is strictly required.
2. **A blockquote summary.** One or two sentences, immediately under the title, stating what the service is for. A reader that fetches only the first few lines still learns enough to decide whether to read further.
3. **H2 sections of annotated links.** Each section groups related entries; each entry is a Markdown link, optionally followed by a colon and a short note on what the link leads to. This is the curated index itself: not the content, a pointer to it, with enough context in the annotation for a reader to decide whether to follow the link without fetching it first.
4. **An Optional section.** A list of secondary links a reader under a tight context budget can skip. Nothing else in the document signals priority; this is the one place the convention gives a reader explicit permission to read less.

The minimal end-to-end example contracts/08-llms.txt in this catalog shows the shape with nothing extra:

```
# Order Service

> Agent-facing order service: place an order and look one up by id.

## API reference

- [OpenAPI document](/q/openapi): the full generated contract

## Operations

- [placeOrder](/q/openapi#/operations/placeOrder): POST /orders, Place a new order.
- [getOrder](/q/openapi#/operations/getOrder): GET /orders/{id}, Look up an order by id.
```

Title, summary, one H2 section of annotated links. No Optional section here because there is nothing secondary to defer; a larger service earns one once some of its surface becomes secondary enough to skip under a tight budget.

An MCP tool surface adapts the same shape to a different kind of entry. A tool has no separate page or URL of its own, so contracts/31-llms.txt lists each one as a name and a description with no link at all:

```
# Publish the Tool Surface

> Agent-facing order status tools, orderStatus, paymentStatus and shipmentTracking, served over Streamable HTTP, stdio and llms.txt from one versioned build.

Version: 1.0.0

## Tools

- orderStatus: Get an order's composed status: its own state, its payment status and amount, and its shipment carrier, tracking number and status, all in one response. Use this instead of calling the order, payment and shipment reads separately.
- paymentStatus: Get only the payment status and amount for an order id, with nothing about the order's own state or its shipment.
- shipmentTracking: Get only the shipment carrier, tracking number and status for an order id, with nothing about the order's own state or its payment.
```

The convention's letter calls for a hyperlink in every entry; its spirit calls for an annotated, curated list a reader can act on without fetching anything else first. A tool name plus the same description already registered on the tool itself satisfies the spirit even where there is no second page to link to.

## Rendering it from a live tool surface instead of maintaining it by hand

The difference between these two examples is not the shape of the file. It is where each one's content comes from.

An `/llms.txt` written by hand is a second description of a surface that already describes itself somewhere else, an OpenAPI document, a set of `@Tool`-annotated methods, a CDI bean that already owns every operation's id and summary. The moment that second description exists, it can disagree with the first, and nothing forces the two back into agreement. An operation added to the real surface and forgotten in the hand-maintained file leaves a reader of `/llms.txt` unaware a capability exists; one added to the file but never shipped sends a reader toward a tool that is not there.

The fix is the same one a shared metadata bean gives a human portal and a machine-readable summary rendered from one source: render `/llms.txt` from the surface's own live registry, on every request, rather than copying that registry's contents into the file once and letting the two drift.

For an MCP tool surface, that live registry is the framework's own `ToolManager`, the object the server already consults to answer a tool-list request over the wire. A metadata bean injects it, iterates it, and sorts the result by name so the rendered order is stable across requests and across serializer changes even though a registry's own iteration order is not a contract:

```java
public List<ToolInfo> tools() {
    List<ToolInfo> result = new ArrayList<>();
    for (ToolManager.ToolInfo tool : toolManager) {
        result.add(new ToolInfo(tool.name(), tool.description()));
    }
    result.sort(Comparator.comparing(ToolInfo::name));
    return result;
}
```

The resource that serves `/llms.txt` then does nothing but format what that bean hands it, title, summary, a version string read from the same build property every other surface reports, and one line per tool:

```java
out.append("# ").append(metadata.title()).append("\n\n");
out.append("> ").append(metadata.summary()).append("\n\n");
out.append("Version: ").append(metadata.version()).append("\n\n");
out.append("## Tools\n\n");
for (ToolSurfaceMetadata.ToolInfo tool : metadata.tools()) {
    out.append("- ").append(tool.name()).append(": ").append(tool.description()).append('\n');
}
```

A name or a description added to a `@Tool`-annotated method shows up in `/llms.txt` the next time it is requested, with no second edit anywhere. There is exactly one place a tool's name and description are written, the annotation on the method itself; `/llms.txt` is a rendering of that place, never a second place of its own.

{% include excalidraw.html file="aa-llms-txt-in-depth" alt="The llms.txt convention's four pieces, an H1 title, a blockquote summary, H2 sections of annotated links and an Optional section, rendered on every request from a live tool registry rather than copied once into a hand-maintained file" caption="Figure A.1: llms.txt rendered from the live tool surface, not hand-maintained" %}

## llms.txt next to robots.txt, sitemap.xml and OpenAPI

Four well-known files sit at predictable paths near the root of a service, which invites treating them as variations on one idea, "a file that tells a machine about the site." They answer four different questions for four different readers:

| File | Audience | Question it answers | Completeness |
|---|---|---|---|
| `robots.txt` (RFC 9309) | a crawler, before it fetches anything | which paths may this crawler request at all | a set of allow/disallow rules, without a content list |
| `sitemap.xml` (sitemaps.org protocol) | a crawler already permitted to crawl | every URL this site has, so none are missed | exhaustive, every page |
| OpenAPI document | a code generator, a client library, or a human reading the full reference | the complete, typed shape of every operation, parameter, schema and response | exhaustive, every operation and every field |
| `/llms.txt` | an LLM (or a human) deciding what to read next | what matters here, and where the detail for each thing lives | curated, a chosen subset |

`robots.txt` governs permission, not discovery: a crawler reads it to decide whether a request is allowed, never to learn what the site contains. `sitemap.xml` governs discovery for a crawler that has already decided to crawl, an exhaustive list built so a search index misses nothing, which is exactly the property `/llms.txt` does not want: an agent with a bounded context window needs fewer, better-annotated entries, not every URL the service has ever published. An OpenAPI document is complete in the other direction, every field of every schema, machine-readable enough that a client can be generated from it without a human reading a line of prose. That completeness is the document's job and also the reason it is the wrong thing to hand a model before the model has decided which operation to call. `/llms.txt` sits in front of all three: a short list naming what exists, with a line explaining why it matters, and a link to the OpenAPI document, a tool definition or a deeper page for the reader that wants the full shape next.

## What belongs in it, for an agent caller versus a human

A human landing on `/llms.txt` directly is reading it as a plain-text sitemap at best; the file is written for a reader that parses structure, not one that expects a styled page. What belongs in it follows from that reader:

- A title and summary short enough to decide relevance from the first two lines alone.
- One entry per operation or tool that exists to be called, not one per internal detail that supports it.
- A note on each entry stating what the operation does and, where it matters, when to prefer it over a similar one, the same distinction `orderStatus` draws against calling `paymentStatus` and `shipmentTracking` separately.
- A link to the full machine schema, an OpenAPI document or a tool's own registered definition, for the reader that needs the complete shape rather than the index.

What does not belong: prose written for a human audience evaluating the service commercially, a changelog, a marketing summary, or any content that exists to be read start to finish rather than scanned for the one entry a caller needs. An agent-facing `/llms.txt` is a table of contents rather than a landing page.

## Anti-patterns

- **A stale, hand-maintained file.** Writing `/llms.txt` once and editing it on a remember-to-update basis guarantees it is correct on the day it is written and wrong on every day after a change to the real surface that nobody updates it for. Render it from the live registry instead, the same tool-manager-backed metadata bean a tool surface already consults for its own handshake.
- **Dumping full documentation into the index.** Pasting an entire API reference, a tutorial or a changelog into `/llms.txt` defeats the reason it exists: a reader with a bounded context window needed a curated list of what matters, the exact thing the index was supposed to save it from reading in full.
- **Treating it as a sitemap.** Listing every page a site has, rather than the subset worth an agent's attention, reproduces `sitemap.xml`'s job under a different name and loses the one property `/llms.txt` was meant to add: an opinionated cut of what to read first.
- **Skipping the Optional section entirely on a surface large enough to need it.** Once a service has secondary entries safe to defer, leaving everything in one undifferentiated list hands a reader under a tight budget no way to tell which three entries to read and which thirty to skip.

## References

- `llms.txt`: [llmstxt.org](https://llmstxt.org/), proposed by Jeremy Howard, September 2024
- RFC 9309: Robots Exclusion Protocol, IETF, September 2022: [rfc-editor.org/rfc/rfc9309.html](https://www.rfc-editor.org/rfc/rfc9309.html)
- Sitemaps XML protocol: [sitemaps.org/protocol.html](https://www.sitemaps.org/protocol.html)
- OpenAPI Specification 3.1.1: [spec.openapis.org/oas/v3.1.1](https://spec.openapis.org/oas/v3.1.1)
- Model Context Protocol specification: [modelcontextprotocol.io](https://modelcontextprotocol.io/specification/2025-11-25)
