# POST /ingest/orders: guardrail response fragment

Not a full OpenAPI document: the two guardrail response shapes `examples/36-traffic-and-resource-guardrails`
maps its exceptions to, both `application/problem+json`.

```yaml
responses:
  "202":
    description: the payload cleared both guardrails and was accepted
    content:
      application/json:
        schema:
          type: object
          required: [orderId, status]
          properties:
            orderId: { type: string, example: "O-9101" }
            status: { type: string, enum: [ACCEPTED] }
  "400":
    description: the payload failed the registered ingest schema
    content:
      application/problem+json:
        schema:
          type: object
          required: [type, title, status, detail, violations]
          properties:
            type: { type: string, example: "https://errors.patterncatalyst.dev/problems/ingest-schema-invalid" }
            title: { type: string, example: "Payload failed schema validation" }
            status: { type: integer, example: 400 }
            detail: { type: string }
            violations:
              type: array
              items: { type: string }
              example: ["$.quantity: must have a minimum value of 1"]
  "429":
    description: the caller exceeded the configured ingest rate limit for the current window
    content:
      application/problem+json:
        schema:
          type: object
          required: [type, title, status, detail, remedy]
          properties:
            type: { type: string, example: "https://errors.patterncatalyst.dev/problems/ingest-rate-limited" }
            title: { type: string, example: "Too many requests" }
            status: { type: integer, example: 429 }
            detail: { type: string }
            remedy: { type: string, example: "wait for the current rate-limit window to elapse, then retry" }
```

The 429 body never carries a bare framework default: `io.smallrye.faulttolerance.api.RateLimitException`
(thrown by the `@RateLimit` interceptor on `IngestResource.ingestOrder` before the method body runs) is mapped by a
`@ServerExceptionMapper` to this shape. The schema referenced for the request body is the committed
`contracts/36-ingest.schema.json` (draft-07).
