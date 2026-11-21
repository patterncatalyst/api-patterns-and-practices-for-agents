// deck.js: "Building Agent-Facing APIs" (Deck 2 of 3)
// Pattern-catalog tour of the Build patterns: synchronous APIs, MCP packaging,
// asynchronous flows. Source: API Patterns & Practices for Agents and LLMs,
// chapters 03-21.
//
// Build:  node deck.js
// Paths:  deck-helpers.js reads DECK_ASSETS (./assets) and DECK_PNG (./png).

"use strict";

const fs = require("fs");
const H = require("./deck-helpers.js");
const {
  COLOR, FONT, W, ASSETS, PNG,
  newDeck, addFooter, addContentTitle, addBullets, addTwoColBullets,
  addStatusTable, addCaption, addCodeSlide, addDiagramSlide, addSectionDivider, addNotes,
} = H;

const OUT = "./building-agent-apis-r17.0.pptx";
const REV = "r17.0";

const pres = newDeck();
pres.title = "Building Agent-Facing APIs";
pres.author = "Pattern Catalyst";
pres.company = "Pattern Catalyst";
pres.subject = "API Patterns & Practices: APIs for Agents & LLMs";

let pageNum = 0;

function S() {                       // new content slide with footer (page no. + logo)
  const s = pres.addSlide(); pageNum += 1; addFooter(s, pageNum); return s;
}
function divider(code, title, subtitle, notes) {   // section divider slide
  const s = pres.addSlide(); pageNum += 1; addSectionDivider(s, code, title, subtitle); addNotes(s, notes);
}

// A pattern slide: title, two short lines (problem / negative witness), then
// a diagram sized to leave room for those lines above it.
function patternSlide(eyebrow, title, bulletLines, pngName, caption, notes) {
  const s = S();
  addContentTitle(s, eyebrow, title);
  addBullets(s, bulletLines, { y: 1.70, h: 0.98, w: 12.09, fontSize: 14.5 });
  const x = 2.10, y = 2.78, w = 9.10, h = 3.55;
  const file = `${PNG}/${pngName}.png`;
  const head = fs.readFileSync(file);
  const pw = head.readUInt32BE(16);
  const ph = head.readUInt32BE(20);
  const scale = Math.min(w / pw, h / ph);
  const fw = pw * scale;
  const fh = ph * scale;
  s.addImage({ path: file, x: x + (w - fw) / 2, y: y + (h - fh) / 2, w: fw, h: fh });
  addCaption(s, caption, 6.48);
  addNotes(s, notes);
  return s;
}

// ---- Cover ---------------------------------------------------------------
{
  const s = pres.addSlide();
  pageNum += 1;
  s.background = { color: COLOR.white };
  try { s.addImage({ path: `${ASSETS}/cover-panel.png`, x: 0, y: 0, w: W, h: 7.5 }); } catch (e) {}
  s.addText("API PATTERNS & PRACTICES FOR AGENTS AND LLMS", { x: 6.00, y: 1.98, w: 6.90, h: 0.34,
    fontFace: FONT.title, fontSize: 13, bold: true, color: COLOR.red, charSpacing: 4, align: "left", valign: "middle" });
  s.addText([{ text: "Building Agent-Facing", options: { breakLine: true } }, { text: "APIs" }], {
    x: 5.95, y: 2.42, w: 6.95, h: 2.00, fontFace: FONT.title, fontSize: 50, bold: true, color: COLOR.ink, align: "left", valign: "top" });
  s.addText("The pattern catalog: synchronous APIs, MCP packaging, and asynchronous flows.", { x: 6.00, y: 4.65, w: 6.70, h: 0.90,
    fontFace: FONT.body, fontSize: 17, italic: true, color: COLOR.caption, align: "left", valign: "top" });
  s.addText(REV, { x: 11.85, y: 5.85, w: 0.95, h: 0.30, fontFace: FONT.mono, fontSize: 11, color: COLOR.caption, align: "right", valign: "middle" });
  try { s.addImage({ path: `${ASSETS}/logo-candidate-2.png`, x: 11.10, y: 6.80, w: 1.55, h: 0.37 }); } catch (e) {}
  addNotes(s, "This is the second of three companion decks for the catalog. The first covers the agent-facing landscape and the forces behind it; this one walks the Build patterns themselves, synchronous endpoints, MCP packaging, and asynchronous flows; the third covers running and securing them in production. Every pattern here follows the same spine: a problem a chatty or opaque API creates for an agent caller, a solution stated in one sentence, and a negative witness, what breaks when the guard is removed. The examples are Quarkus and Camel, but every pattern states a stack-neutral contract first.");
}

// ======================= SYNC ======================= //
divider("SYNC", "Build: synchronous APIs",
  "Ten patterns for request and response surfaces an agent can call directly.",
  "This section covers ten patterns for the request and response surface: collapsing chatty call sequences into one intent-named endpoint, replacing pagination sweeps with typed search, batch and count shapes, safe retries, contracts an agent can actually bind to, token-efficient responses, instructive errors, a preview step before an irreversible write, and a single bean shared across REST, GraphQL and MCP. Three of these, Intent Endpoint, Idempotency, and Propose/Preview/Commit, get a second slide each with a short code callout, because the guarantee lives in a specific few lines.");

// 03 Intent Endpoint over CRUD (headline, 2 slides)
patternSlide("SYNC · PATTERN", "Intent Endpoint, over CRUD",
  [
    "Problem: a chatty, CRUD-shaped sequence leaves a window where the order reads CANCELLED and the payment still reads COMPLETED.",
    "Without the guard: removing the transaction boundary lets the order update commit alone while the payment update never runs.",
  ],
  "03-intent-endpoint",
  "One endpoint, named for the business operation, one transaction.",
  "A chatty CRUD surface forces a caller to sequence two calls that belong together, update the order then create the refund, with an observable half-done state in between. POST /orders/{id}/cancel-and-refund collapses both into one intent-named endpoint backed by one @Transactional saga method, so a reader never sees the operation half done. Cancelling an order that is not cancellable returns 409, an unknown id returns 404. Removing the transaction boundary and forcing a failure between the two updates is the negative witness: the order update commits alone, auto-committed, and the payment never moves, the exact partial state the pattern exists to prevent. The trade-off is composability: a caller that only wants to cancel without a refund has no narrower primitive here.");

{
  const s = S();
  addCodeSlide(s, "SYNC · CODE", "Intent Endpoint: one saga, one transaction", "Java · Quarkus + Camel",
    [
      "@Transactional",
      "public OrderCancelledEvent cancelAndRefund(String orderId, String reason) {",
      "    // one transaction, two updates",
      "    updateOrderStatus(c, orderId, OrderStatus.CANCELLED);",
      "    updatePaymentStatus(c, orderId, PaymentStatus.REFUNDED);",
      "    return new OrderCancelledEvent(orderId, reason, Instant.now().toString());",
      "}",
    ],
    "Either both updates commit, or neither does.");
  addNotes(s, "The whole guarantee lives in this one method. Both updates run against the same database inside the same @Transactional boundary, so they commit together or not at all. The Camel route that calls this saga never terminates the HTTP request itself; it runs the saga and publishes the resulting event on the order's key. Nothing here is Camel machinery, it is a plain transactional method, which is the point: the EIP boundary is the saga, not the HTTP layer. Ask the room what happens if this method is split into two separate calls from the route instead of one method, and let them find the gap themselves before you show the negative witness again.");
}

// 04 Search over Pagination
patternSlide("SYNC · PATTERN", "Search, over Pagination",
  [
    "Problem: a caller who does not know where a row sits has no way to skip ahead in raw offset pagination.",
    "Without the guard: an endpoint that ignores its own filter still returns in one cheap call, but with the wrong rows.",
  ],
  "04-search-over-pagination",
  "Same 60-row table: one search call, or a six-page sweep.",
  "Raw offset pagination answers what is on this page, never which rows match. GET /orders/search binds typed, optional filter fields server-side and returns only the matching rows, capped, in one call. Against a 60-row seed, one filtered call returns three matching orders in one round trip and 309 bytes; a pagination sweep over the same table needs six round trips and 6,479 bytes to see every row. The negative witness is subtle: an endpoint that silently ignores its filter still looks cheap, one call, one round trip, but returns every status and every SKU instead of the three expected matches. A cheap call that returns the wrong rows is worse than a slow sweep that eventually returns the right ones.");

// 05 Batch Operation and Count Flag
patternSlide("SYNC · PATTERN", "Batch Operation and Count Flag",
  [
    "Problem: resolving known ids one at a time, or fetching every row to count them, charges a caller for a full record it never asked for.",
    "Without the guard: a count endpoint returning full records degrades from a number to a JSON array.",
  ],
  "05-batch-and-count",
  "Batch-get keyed hits and misses; count answers with a bare number.",
  "POST /orders/batch-get resolves many known ids in one call, hits keyed by id, misses named explicitly in a notFound list. GET /orders/count answers how many with a bare number, never a record. Measured against a 36-row seed, a 10-id batch call costs one round trip and 933 bytes against 10 round trips for the same ids fetched one at a time; the count contrast is sharper still, 2 bytes against 3,718 bytes to fetch every row and count client-side. The negative witness is the most visible kind: change the count endpoint to return the full record list and the response stops being a number and becomes an array, the exact shape the count flag exists to avoid. An unbounded id array on the batch endpoint just trades one unbounded-response problem for another.");

// 06 Idempotency and Safe Retries (headline, 2 slides)
patternSlide("SYNC · PATTERN", "Idempotency and Safe Retries",
  [
    "Problem: a caller retrying after a timeout cannot tell a failed first attempt from one whose response was simply lost.",
    "Without the guard: two calls carrying the same key and body each run the saga in full, writing two orders and publishing two events.",
  ],
  "06-idempotency",
  "Safety comes from the server's own guard, never caller discipline.",
  "An agent that retries a timed-out write has no way to know whether the first attempt landed. A required Idempotency-Key header, enforced by a server-side Idempotent Consumer backed by durable storage, replays the first response byte for byte on a repeat key and rejects a repeat key carrying a different body with 409. A missing key is rejected with 400 before anything is written. The hash behind the replay check covers the request's field values, not raw JSON bytes, so field order or whitespace in a retry never causes a false conflict. Removing the Idempotent Consumer wrapper is the negative witness: every call runs the saga unconditionally, so a retry with the same key and body writes a second order and publishes a second event, and the conflict check stops rejecting anything because nothing is comparing against a stored response anymore.");

{
  const s = S();
  addCodeSlide(s, "SYNC · CODE", "Idempotency: the guard, not caller discipline", "Java · Camel route",
    [
      "from(\"direct:place-order\")",
      "    .idempotentConsumer(header(IDEMPOTENCY_KEY_HEADER), repository)",
      "    .skipDuplicate(false)",
      "    .choice()",
      "        .when(exchangeProperty(Exchange.DUPLICATE_MESSAGE).isEqualTo(TRUE))",
      "            .process(replayOrReject)",
      "        .otherwise()",
      "            .process(placeNewOrder)",
      "    .end();",
    ],
    "skipDuplicate(false) keeps the repeat flowing so the route can branch and reply.");
  addNotes(s, "This is the Idempotent Consumer EIP from Hohpe and Woolf, applied at the HTTP boundary. skipDuplicate(false) is the key detail: the default behavior silently drops a duplicate exchange, which would leave a retried caller with no response at all. Keeping it flowing lets the route check Exchange.DUPLICATE_MESSAGE and branch to replayOrReject, which loads the stored response and either replays it on a matching body hash or returns 409 naming the order the key already belongs to. A first-time key runs placeNewOrder inside one transactional method that writes the order row and its stored response together. Point out that this repository is backed by durable storage, a database table, not an in-memory map, because the guarantee has to survive a restart.");
}

// 07 Strict Self-Describing Contract
patternSlide("SYNC · PATTERN", "Strict Self-Describing Contract",
  [
    "Problem: a hand-maintained OpenAPI document states what its author remembers, not what the service actually enforces.",
    "Without the guard: stripping an enumeration annotation leaves the generated schema showing only type: string, no legal values.",
  ],
  "07-self-describing-contract",
  "A contract generated from the code cannot drift, because it is the code.",
  "An agent never reads the Java behind an endpoint; it reads whatever document describes it and calls based on what that document says. A precise OpenAPI 3.1 document, generated from annotated code at build time and committed as the record of what was generated, is the instruction set an agent actually binds to: a stable operationId per operation, every required field marked, every status value enumerated. The committed contract lets a later run's document be diffed against it, so drift is something a diff catches rather than something a reader notices by eye. The negative witness strips the annotations one at a time: no enumeration leaves a bare string, no required marking leaves no required array, no operationId leaves the operation unnamed, while the service itself keeps answering every call correctly. Only the contract loses the information that made it strict.");

// 08 Semantic Metadata and llms.txt
patternSlide("SYNC · PATTERN", "Semantic Metadata and llms.txt",
  [
    "Problem: a human portal and a machine-readable llms.txt, each its own write-up, are never in sync, only a matter of when they drift.",
    "Without the guard: a hardcoded line bypassing the shared bean names an operation the portal never mentions.",
  ],
  "08-dual-use-portal",
  "One metadata bean, two renderings, never two sources of truth.",
  "A human lands on a documentation portal; a model reads /llms.txt instead, a plain-text summary in the llmstxt.org shape. Writing each surface from its own hand-maintained list looks harmless until an operation is added to one and forgotten in the other. One CDI bean, ApiMetadata, owns every operation's id, method, path and summary; both the portal and the llms.txt resource read it fresh on every request, so the two can never list a different operation set because there is only one list to read from. The guarantee is literally set equality: the operation ids extractable from each surface must match exactly. Bypassing the bean for one hardcoded line in the llms.txt resource breaks that equality immediately, reproducing in miniature the drift the shared bean exists to prevent.");

// 09 Token-Efficient Response / BFF
patternSlide("SYNC · PATTERN", "Token-Efficient Response, Agent BFF",
  [
    "Problem: a full human payload carries internal ids, audit trails and notes an agent task never uses.",
    "Without the guard: a summary endpoint that just calls the full read still costs the same bytes as the full payload.",
  ],
  "09-agent-bff",
  "Reshape the read with its own query, do not just rename the response.",
  "Every field in a response an agent reads shares the same bounded context window as its instructions and tool definitions. A purpose-built projection, backed by its own narrow SQL query rather than the full entity trimmed after loading, returns exactly the fields a task needs. Measured on the same order, the full REST payload is 571 bytes, the summary projection is 110 bytes, and a GraphQL query selecting two fields is 62 bytes. The negative witness changes the summary endpoint to call the full read internally instead of its own narrow query: the projection then costs the same 571 bytes as the full payload, with every internal field still present, because wrapping under a friendlier name never removed the underlying weight.");

// 10 Instructive Error (RFC 9457)
patternSlide("SYNC · PATTERN", "Instructive Error, RFC 9457",
  [
    "Problem: a bare status code tells an agent only that a call failed, with nothing to decide what to try next.",
    "Without the guard: removing the exception mapper falls through to a generic 500 with no type, no detail, no remedy.",
  ],
  "10-instructive-error",
  "A remedy is not the same information as a status code.",
  "A human who hits an error reads the page or asks a colleague; an agent has only the text the response actually contains. Every domain exception maps to an RFC 9457 problem response naming type, title, status and detail, plus a remedy member specific to that failure: a not-cancellable order names its currentState and allowedTransitions, computed from the lifecycle rule itself rather than a hardcoded list. A SHIPPED order's remedy names DELIVERED as the only reachable state; cancelling an already-cancelled order names an empty allowedTransitions. Commenting out the three exception mappers is the negative witness: every case falls through to the framework's default, a bare 500 served as text/plain, collapsing three distinct, actionable failures into one an agent cannot act on.");

// 11 Propose, Preview, Commit (headline, 2 slides)
patternSlide("SYNC · PATTERN", "Propose, Preview, Commit",
  [
    "Problem: an endpoint that only ever commits gives an agent one way to learn a write's effect, running it.",
    "Without the guard: dropping the dry-run check silently cancels the order the caller only meant to preview.",
  ],
  "11-propose-preview-commit",
  "See the effect before it becomes permanent.",
  "An agent about to cancel an order and refund its payment cannot undo that action once it runs. A dryRun flag on the same intent endpoint, not a separate preview path, returns a structured review of what committing would do: current state, the resulting state, the refund amount, what would publish, and changes nothing. A REJECTED_BY_USER decision is a third, graceful outcome, a plain 200 no-op, distinct from an error. The negative witness removes the one guard the whole pattern rests on: every request falls through to commit regardless of the dryRun flag, so a preview call silently cancels the order, and a real commit attempted afterward is rejected as a duplicate, because the order the caller still believes is untouched has already moved.");

{
  const s = S();
  addCodeSlide(s, "SYNC · CODE", "Propose, Preview, Commit: one endpoint, three outcomes", "Java · Quarkus REST",
    [
      "public Response cancel(String id, CancelRequest request) {",
      "    if (request.isRejectedByUser()) {",
      "        return ok(new NoActionResult(id, \"REJECTED_BY_USER\"));",
      "    }",
      "    if (request.isDryRun()) {",
      "        return ok(reviewService.review(id));",
      "    }",
      "    return commit(id, request.reason());",
      "}",
    ],
    "The review computes the same guard and the same result the commit would produce.");
  addNotes(s, "Walk the three branches in order: decline is a graceful no-op, preview computes the same existence and lifecycle guard the commit path enforces and returns a structured review without opening a write transaction, and only the fall-through actually commits. The key point is that the review has to compute the same effect the commit produces, or the two diverge and the preview misreports what committing will do. This is also the slide to name the trade-off: a preview step doubles the paths through the endpoint that have to agree with each other, so it earns its cost on irreversible actions, not on every write.");
}

// 12 MCP-First Single CDI Bean
patternSlide("SYNC · PATTERN", "MCP-First Single CDI Bean",
  [
    "Problem: three faces, REST, GraphQL and an MCP tool, reimplementing the same read independently give no guarantee they ever agree.",
    "Without the guard: diverging one face from the shared bean still passes a check on that face alone, while cross-face comparison fails.",
  ],
  "12-mcp-first-bean",
  "One bean behind three transports never drifts, because it never duplicates.",
  "A fix applied to one face and forgotten in the other two is not a hypothetical, it is the default outcome of maintaining the same logic three times. OrderOperations holds the read once; the REST resource, the GraphQL query and the MCP @Tool each delegate to that one method and return whatever it returns, nothing more. An initialize handshake negotiates the MCP protocol revision, and tools/list advertises the tool with a schema naming one required argument. The negative witness changes the GraphQL face to stop delegating and return a different value for one field: that face still runs and still answers, so a check on it alone would pass, but a check comparing all three faces fails, because only two of them still agree.");

// ======================= MCP ======================= //
divider("MCP", "Build: MCP packaging",
  "Four patterns for packaging an existing API as MCP tools, scoped for an agent caller.",
  "This section covers packaging an API as MCP tools: composing narrow reads into a tool shaped for the question an agent actually asks, splitting a wide operation set by domain so tools/list stays small, reshaping a heterogeneous protocol like gRPC at the facade boundary instead of wrapping it unchanged, and gating a tool surface with the same identity discipline any credentialed API already needs. None of these four treat MCP as a free transport swap over an existing API; each one names a specific way a naive 1:1 wrap carries the wrapped API's own problems straight through to the tool surface.");

// 13 Package an API as MCP Tools
patternSlide("MCP · PATTERN", "Package an API as MCP Tools",
  [
    "Problem: wrapping each endpoint as its own tool, one for one, carries the wrapped API's chattiness into the tool surface.",
    "Without the guard: reducing the composed tool to a passthrough of one row leaves the other fields empty without failing the call.",
  ],
  "13-mcp-triad",
  "Compose for the question an agent asks, not for the endpoint it replaces.",
  "A caller asking where an order stands against a literally wrapped API makes three separate tool calls and reassembles the answer itself. A tool, resource, prompt triad composes the three underlying reads into one orderStatus tool response, backs it with a resource a caller fetches once instead of re-deriving the same rules on every call, and a prompt that calls the same composed method the tool calls, so the two never disagree. The narrower reads stay available on their own for a caller who only wants one of them. Reducing orderStatus to a passthrough of the raw order row, leaving payment and shipment fields empty, is the negative witness: the call still succeeds, but the fields the composition exists to deliver come back blank.");

// 14 Domain-Split Toolset + Progressive Discovery
patternSlide("MCP · PATTERN", "Domain-Split Toolset, Progressive Discovery",
  [
    "Problem: one server exposing every plausible operation grows tools/list past what a model can reliably choose from.",
    "Without the guard: a duplicate tool name from a careless merge is rejected at build time, before any test or container runs.",
  ],
  "14-domain-split-discovery",
  "Small live toolsets per domain, a shared catalog for the rest.",
  "Picking accuracy degrades as a tool list grows; a twenty-operation domain does not need all twenty live to be usable. Two named, domain-scoped servers each keep exactly two live, namespaced tools; a shared catalog resource and a search tool let a caller discover and read about the other sixteen operations without either ever becoming a live tool call. The framework itself, not a reviewer, is what catches the failure mode this pattern guards against: two @Tool methods landing on the same name, which a careless merge might produce by dropping a domain prefix, fails the build during augmentation rather than colliding silently at runtime on MCP's flat tool namespace.");

// 15 Reshape not Wrap
patternSlide("MCP · PATTERN", "Reshape, not Wrap",
  [
    "Problem: passing a gRPC service's wire format through unchanged relocates its integers, enums and status codes one hop closer to the agent.",
    "Without the guard: bypassing the decimal reshape turns a 49.99 payment into 49, and the call still succeeds.",
  ],
  "15-reshape-grpc",
  "Reshape at the boundary, in both directions, every time.",
  "proto3 has no decimal type, so a payment service represents 49.99 as the integer 4999. A facade in front of a separate gRPC service reshapes every value crossing that boundary, decimal to cents on the way in, cents back to decimal on the way out, and turns a gRPC StatusRuntimeException into an instructive tool error before it ever reaches the caller. The payment service's own gRPC port is never published to a host; the facade's Streamable HTTP endpoint is the only surface a caller reaches. The negative witness skips the reshape on the way in: the round trip still completes and the call still succeeds, but the amount an agent reads back is wrong by two orders of magnitude.");

// 16 Securing and Gating MCP Tools
patternSlide("MCP · PATTERN", "Securing and Gating MCP Tools",
  [
    "Problem: an MCP tool left open, or gated identically regardless of what it does, gives a model the same destructive reach as a read.",
    "Without the guard: removing the scope check lets an under-scoped token reach a confirmation step it should never reach.",
  ],
  "16-gated-mcp-tools",
  "Each tool adds its own requirement on top of the endpoint's own gate.",
  "An agent with private data, untrusted content and an unguarded path to act on both is the lethal trifecta; an open MCP surface is exactly that path. The whole endpoint requires a verified bearer token before any tool is dispatched, a role gates which caller may invoke a read, and a destructive write adds a scope requirement plus an explicit Elicitation confirmation before anything writes. A declined confirmation returns a typed rejected result and writes nothing, the same as a human saying no. The negative witness removes only the scope check: the confirmation step still exists, but a caller who should never have reached it gets there anyway, isolating exactly the gate the pattern depends on.");

// ======================= ASYNC ======================= //
divider("ASYNC", "Build: asynchronous APIs",
  "Five patterns for events, workflows and audit trails that outlive one request.",
  "This section moves off the request/response boundary entirely: waking an agent on a Kafka event instead of polling, coordinating several agents through events on shared topics with no orchestrator, holding an irreversible action for human approval with a mechanism matched to how long that approval might take, running a multi-step workflow as a saga with named compensations, and recording an agent's own actions on an append-only log a human can audit and the agent itself can replay. Every one of these trades a synchronous guarantee for a durable one.");

// 17 Event-Triggered Agent Wakeup
patternSlide("ASYNC · PATTERN", "Event-Triggered Agent Wakeup",
  [
    "Problem: a poll loop ties reaction time to an interval that is always wrong in one direction or the other.",
    "Without the guard: a stopped consumer leaves the event exactly where it fell, with no record written until it resumes.",
  ],
  "17-event-triggered-wakeup",
  "The event wakes the agent; the tool call it makes is what the checks observe.",
  "A poll loop either wastes calls finding nothing changed or leaves an agent acting on stale information, and every interval is wrong in one of those two directions. A thin Camel Kafka consumer wakes the agent's own decision call the instant a relevant event arrives; the tool call that decision makes, not the consumer, performs the work and leaves the durable record a check observes. A disabled consumer never loses a message, a Kafka consumer group only delays reading one, so an event published while the route is down sits at its committed offset until the route resumes. The negative witness is that silence: stop the consumer and a second event sits unconsumed with no record ever written for it, until the consumer comes back and reads it from where it left off.");

// 18 Multi-Agent Choreography
patternSlide("ASYNC · PATTERN", "Multi-Agent Choreography",
  [
    "Problem: an orchestrator calling two other agents directly knows their interfaces by name and breaks whenever either changes.",
    "Without the guard: closing one publish filter drops an event silently, stalling exactly that hop while earlier agents' work stands.",
  ],
  "18-multi-agent-choreography",
  "Cutting one link stalls one hop; the agents before it finish regardless.",
  "An orchestrator that calls agent B then agent C from inside agent A's own method is an orchestrator wearing an agent's clothing, still coupled to both of their interfaces. Each agent here reacts only to the event the step before it produced and publishes only the event the step after it needs, with no agent importing or calling another agent's code. The order agent is the only entry point a caller reaches directly; the shipping and notification agents learn an order exists only from a Kafka consumer. A single Camel filter guarding the shipping agent's publish step is the pattern's one intentional cut point: closing it drops the event silently, the order and shipment still write, but the notification agent's consumer sees nothing for that order, isolating the break to exactly that hop.");

// 19 Human-in-the-Loop Gate
patternSlide("ASYNC · PATTERN", "Human-in-the-Loop Gate",
  [
    "Problem: an agent that decides and acts in the same call leaves no seam for a human to intervene before the action takes effect.",
    "Without the guard: a gate the agent can route around, or an approve-by-default timeout, protects nothing.",
  ],
  "19-hitl-gate",
  "A Kafka gate durably outlasts a restart, at the cost of answering out-of-band.",
  "An agent that can both classify a cancellation as fraud and hold the authority to cancel and refund has combined a judgment call with an irreversible action. The agent's tool opens a pending decision and returns, writing neither the order nor the payment; a decision event resumes the held row through a claim that locks it before checking status, so a redelivered APPROVE can never fire twice. Four mechanisms hold an action this way, MCP Elicitation for seconds in one session, a suspended agentic workflow for minutes in one process, a durable flow engine for hours across restarts, and this Kafka pending-decision gate for days with no forced deadline, chosen by how long the response might take and whether the hold must survive a restart. The negative witnesses are blunt: a gate the agent can route around protects nothing, and treating an unanswered decision as approved after a timeout turns the gate into a delay, not a check.");

// 20 Long-Running Agent Workflow
patternSlide("ASYNC · PATTERN", "Long-Running Agent Workflow",
  [
    "Problem: treating a multi-service process as one transaction either locks resources too long or silently stops being transactional.",
    "Without the guard: a non-idempotent compensation breaks the moment a coordinator retries it after a timeout.",
  ],
  "20-saga-compensation",
  "Every compensation is a guarded update: calling it twice changes nothing twice.",
  "Reserving stock, taking payment and booking a carrier span three different concerns and, in a real deployment, three different services; nothing holds one database transaction across all three. The Camel Saga EIP runs each step as its own nested saga with its own named compensation; a failure at any point unwinds only the steps already enlisted, in reverse order, rather than treating the whole flow as one unit. A declined payment is the case the checks drive: the stock reservation compensates, no payment row is ever written, and the carrier step, enlisted after the failing one, never runs at all. The negative witness is a compensation written to run only once: a coordinator that retries it after a timeout finds no guard checking the exact prior state, and the repeat call is no longer safe.");

// 21 Replayable Agent Memory / Event-Sourced Audit
patternSlide("ASYNC · PATTERN", "Replayable Agent Memory, Event-Sourced Audit",
  [
    "Problem: a projection table that is also the system of record has no independent way to check whether it is still correct.",
    "Without the guard: a direct mutation bypassing the log breaks live-versus-replay agreement until the table is rebuilt from the untouched log.",
  ],
  "21-event-sourced-audit",
  "The log is the durable record; rebuilding live from it erases a direct mutation.",
  "An agent's own decisions matter for two reasons at once: the agent may need to recall what happened, and a human needs to audit what it actually did, independent of whatever the current state claims. Every action, the tool call consumed and the model call made, is appended to a log the application never mutates; the live projection used day to day is nothing more than a fold of that log, keyed so a redelivered record changes nothing on a second fold. A replay reads the identical log from the beginning into a scratch table using the same fold logic, and the two must always agree. A gated demo endpoint is the negative witness: it updates a projection row in place without appending to the log, and only rebuilding the live table from the untouched log restores the match.");

// ---- Closing + References -------------------------------------------------
{
  const s = S();
  addContentTitle(s, "CLOSING", "The arc, and where the sources are");
  addTwoColBullets(s,
    [
      "Synchronous APIs collapse chatty sequences into intent, search, batch, and safe-retry shapes an agent can trust on the first call.",
      "MCP packaging composes for the question an agent asks, splits by domain, reshapes heterogeneous protocols, and gates like any credentialed API.",
      "Asynchronous flows wake an agent on events, coordinate several agents by choreography, hold irreversible actions for a human, and leave a replayable record.",
    ],
    [
      { text: "JJ Geewax, API Design Patterns, Manning, 2021: resource-oriented design, pagination, batch operations, the error model.", muted: true },
      { text: "Gregor Hohpe and Bobby Woolf, Enterprise Integration Patterns, Addison-Wesley, 2003: the pattern form itself, and Idempotent Consumer, Saga, Message Filter behind the async patterns.", muted: true },
      { text: "RFC 9457, Problem Details for HTTP APIs: the instructive error wire format.", muted: true },
      { text: "Model Context Protocol specification, modelcontextprotocol.io: tools, resources, prompts, progressive discovery.", muted: true },
      { text: "Leonard Richardson, Mike Amundsen, Sam Ruby, RESTful Web APIs, O'Reilly, 2013; llms.txt (llmstxt.org); Simon Willison on the lethal trifecta; Anthropic engineering on effective tools and context.", muted: true },
    ],
    { fontSize: 15 });
  addNotes(s, "Recap the three sections in order and land on the shared spine: every pattern here is a problem a chatty or opaque API creates for an agent caller, one sentence of solution, and a negative witness naming exactly what breaks when the guard is removed, not a hypothetical. Point to the References column for the sources this catalog draws its pattern vocabulary and specifications from, Geewax for the API-design forms, Hohpe and Woolf for the EIP vocabulary underneath the async half of this deck, RFC 9457 and the MCP specification for the two wire formats most of these patterns are built on. The next deck in this series covers running these same patterns in production: identity, traffic shaping, and observability.");
}

pres.writeFile({ fileName: OUT })
  .then(p => console.log("WROTE", p))
  .catch(e => { console.error(e); process.exit(1); });
