# 36 Traffic and resource guardrails

A single-service example: one agent-facing ingest endpoint, `POST /ingest/orders`, fronted by two
guardrails in one app. `@RateLimit` (`io.smallrye.faulttolerance.api`, a SmallRye Fault Tolerance
addition beyond the MicroProfile Fault Tolerance spec) caps the endpoint at 5 calls per 30-second
window; a caller over the limit never reaches the method body at all, since the annotation is a CDI
interceptor that runs first. A call that clears the rate limit is then routed through one Camel
route, `direct:validate-ingest` (`IngestSchemaValidationRoute`), to the Camel JSON Schema Validator
component against the committed, draft-07 `contracts/36-ingest.schema.json`; only a payload that
clears both guardrails is accepted. Both guardrails' rejections throw an exception a global
`@ServerExceptionMapper` (`GuardrailExceptionMappers`) maps to a distinct RFC 9457
`application/problem+json` body, and both record a durable `trafficguardrails.rejections` row plus
a tagged `ingest.rejected` Micrometer counter through the same `RejectionRecorder`.

No Kafka topic in this example: an ingest gate's whole job is deciding whether a call is even let
in, before there is anything to route downstream. The one Camel route this example does carry
exists only to front the schema-validity guardrail with a platform-managed component instead of a
standalone third-party library.

## The two guardrails

**Rate limit.** `IngestResource.ingestOrder` carries `@RateLimit(value = 5, window = 30,
windowUnit = ChronoUnit.SECONDS)`. The rate-limit state is a singleton keyed by the bean class and
the method, not by caller (confirmed against `quarkus-smallrye-fault-tolerance`'s own guide): every
call to the annotated method counts, valid payload or not. An over-limit call throws
`io.smallrye.faulttolerance.api.RateLimitException`, which by default surfaces as a bare HTTP 500;
`GuardrailExceptionMappers.mapRateLimited` maps it to a `429` `RateLimitProblem` instead.

**Schema-validity ingest gate.** `IngestSchemaValidationRoute`, a one-route `RouteBuilder`, exposes
`direct:validate-ingest`, routing the raw body to `json-validator:file:<path>`
(`camel-quarkus-json-validator`, platform-managed by the Camel Quarkus platform BOM, no version pin
of its own) against `contracts/36-ingest.schema.json` (bind-mounted read-only into the container; a
relative path in dev and test). `IngestResource` calls the route through an injected
`ProducerTemplate`; a payload that fails validation throws
`org.apache.camel.component.jsonvalidator.JsonValidationException`, wrapped in a
`CamelExecutionException`. `IngestResource` unwraps the cause, turns each
`com.networknt.schema.Error` into a `"<instance location>: <message>"` string, and throws the same
`SchemaViolationException` `GuardrailExceptionMappers.mapSchemaViolation` maps to a `400`
`ValidationProblem` naming every violation. The component uses NetworkNT JSON Schema internally
(confirmed by `mvn dependency:tree`: `camel-quarkus-json-validator:3.40.0` to
`camel-json-validator:4.22.2` to `json-schema-validator:2.0.7`), but that dependency's version is
resolved by the platform BOM, not pinned by this project.

## The two negative witnesses

- `SCHEMA_GATE_ENABLED=false` (compose environment, `schema.gate.enabled`) makes `IngestResource`
  skip the `direct:validate-ingest` call entirely: an invalid payload is accepted instead of
  rejected.
- `RATE_LIMIT_VALUE_OVERRIDE` / `RATE_LIMIT_WINDOW_OVERRIDE` (compose environment, mapped to
  `quarkus.fault-tolerance.global.rate-limit.value` / `.window`, SmallRye Fault Tolerance's own
  runtime override mechanism for annotation members) raise the effective limit far above what a
  verification burst can reach: the call that should have been the limit's 6th is now a `202`
  instead of a `429`. The annotation's own `value`/`window` members are compile-time constants, so
  the override is global, not per-method; this app only ever has one `@RateLimit` method.

## Run

```bash
./verify.sh
```

`verify.sh` needs Docker Engine with the compose plugin; it builds the service and the shared
domain model inside the image, starts PostgreSQL, Apicurio and LGTM plus the service, runs the
checks below (including both negative witnesses, each restored and reverified), and removes
everything with `down -v`. Set `KEEP=1` to leave the stack running for inspection.

Run the tests alone (Quarkus Dev Services starts PostgreSQL; no compose stack needed). Install the
shared domain model first:

```bash
(cd ../domain-model && mvn -B -q install)
mvn -B verify
```

## Tests and checks in detail

`mvn -B verify` (Dev Services, no compose): `IngestResourceTest` posts a valid payload (`202`) and
an invalid one (`400` problem+json naming its violations, with a new `trafficguardrails.rejections`
row), each call routed live through `direct:validate-ingest` and the Camel JSON Schema Validator
component. `RateLimitBurstTest` runs in its own `QuarkusTestProfile` (a freshly booted
application, so the rate-limit singleton starts at zero), overriding the limit to 3 calls per 30
seconds: 3 valid calls succeed, the 4th is a `429` problem+json, and both the durable row count and the
`ingest.rejected{reason="rate_limit"}` Micrometer counter grow.

`verify.sh` adds the live view: a burst of 6 calls against the real 5-per-30-second limit, the 6th
asserted `429`; a valid payload accepted; an invalid payload rejected; the schema registered in
Apicurio and read back; both rejection reasons visible in Prometheus and in the
`trafficguardrails.rejections` table; both negative witnesses, each restored and reverified.

## Kafka topic ACLs and client quotas: documented, not run live

This example writes to no Kafka topic, so it has nothing to protect with a broker-side guardrail;
the two Kafka-native controls below are the next layer a topic-writing ingest service would add,
documented here with exact, cited configuration rather than stood up against the shared
`examples/_infra` broker, which runs `PLAINTEXT` with no authorizer. Live enforcement of both needs
a SASL-authenticated, authorizer-enabled broker, which this book stands up with the Kubernetes
operator in the running-on-Kubernetes material, not in a local compose stack.

**Topic ACLs**, KRaft mode, `org.apache.kafka.metadata.authorizer.StandardAuthorizer`
([Kafka documentation, Authorization and ACLs](https://kafka.apache.org/documentation/#security_authz)):

```properties
# server.properties
authorizer.class.name=org.apache.kafka.metadata.authorizer.StandardAuthorizer
super.users=User:admin
allow.everyone.if.no.acl.found=false
```

```bash
kafka-acls.sh --bootstrap-server localhost:9092 \
  --add --allow-principal User:ingest-agent \
  --operation Write --topic apa.orders.placed
```

With `allow.everyone.if.no.acl.found=false`, a principal with no matching ACL is denied by default;
only `ingest-agent` can write to `apa.orders.placed`, and no principal can read or write any other
topic without its own grant.

**Client quotas**, `kafka-configs.sh`
([Kafka documentation, Quotas](https://kafka.apache.org/documentation/#design_quotas)):

```bash
kafka-configs.sh --bootstrap-server localhost:9092 \
  --alter --add-config 'producer_byte_rate=1048576,consumer_byte_rate=2097152,request_percentage=200' \
  --entity-type users --entity-name ingest-agent
```

A quota throttles a client that exceeds its byte-rate or request-time share rather than rejecting
it outright: the broker delays responses to bring the client back under its quota, the
broker-side, byte-budget counterpart to this chapter's application-level call-count limit.

## Layout

| Path | Purpose |
|------|---------|
| `src/main/java/.../IngestResource.java` | the trigger: `POST /ingest/orders`, `@RateLimit`, the schema-gate call |
| `src/main/java/.../IngestSchemaValidationRoute.java` | guardrail 2: one route, `direct:validate-ingest`, to the Camel JSON Schema Validator component against the committed draft-07 schema |
| `src/main/java/.../GuardrailExceptionMappers.java` | maps both guardrails' exceptions to problem+json and records the rejection |
| `src/main/java/.../RejectionRecorder.java` | the durable row, the Micrometer counter, the log line |
| `src/main/java/.../RateLimitProblem.java`, `ValidationProblem.java` | the two problem+json shapes |
| `src/main/resources/db/migration/` | Flyway migration for `trafficguardrails.rejections` |
| `../../contracts/36-ingest.schema.json` | guardrail 2's committed, draft-07 schema |
| `../../contracts/36-ingest-responses.md` | the `202`/`400`/`429` response fragment |
| `compose.yaml` | includes the shared infrastructure and LGTM, adds the service |
| `Containerfile` | UBI 10 multi-stage build; context is `examples/` |
| `verify.sh` | the checks above, including both negative witnesses |
