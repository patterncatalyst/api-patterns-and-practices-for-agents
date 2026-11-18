# Contracts

The machine-readable contracts for every pattern in this book land here as
chapters ship:

- **OpenAPI 3.1** documents for the REST patterns in Part 1.
- **GraphQL SDL** schemas for the GraphQL patterns in Part 1.
- **MCP tool manifests** (the JSON a `quarkus-mcp-server` endpoint advertises)
  for the Part 2 packaging chapters.
- **Avro and JSON Schema** documents, registered in Apicurio, for the async
  contracts in Part 3.

This directory is empty in r01. A CI drift gate (comparing the committed
contract to what the running example actually serves) is added when the
first contract lands, alongside the chapter that introduces it.
