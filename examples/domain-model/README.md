# Order-to-delivery domain model

The shared records for the order-to-delivery domain used by every example in
this book, mirroring the shipping domain used by the
event-driven-patterns-with-quarkus-and-camel sibling: `Order`, `InventoryItem`,
`Payment`, `Shipment`, `Notification`, and the `Carrier` enum (FASTLINE,
AIRCO). See `_plans/domain-model.md` for the full model, the event and topic
catalog (`apa.<domain>.<event>`), and the chapter-by-chapter map of which
pattern exercises which part of the domain.

## Status

The buildable Maven module, its records, and its JUnit negative-witness tests
landed in r02, alongside Part 0's chapters and the first wiring of
`examples/_infra/`.

## Use from an example

Examples depend on `com.patterncatalyst.agentapi:domain-model:1.0.0`. Install
it into the local Maven repository from the repository root, then build the
example:

```bash
mvn -B -ntp -f examples/domain-model install
```
