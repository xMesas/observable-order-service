# observable-order-service

[![CI](https://github.com/xMesas/observable-order-service/actions/workflows/ci.yml/badge.svg)](https://github.com/xMesas/observable-order-service/actions/workflows/ci.yml)

An order flow instrumented with real distributed tracing and custom metrics — verified against an actual Zipkin backend and by reading the real log output, not asserted from what Spring Boot's auto-configuration is supposed to do.

## In plain English

Placing an order calls two downstream services (inventory, payments) and runs one piece of in-process business logic (shipping cost) in between. Every hop shows up in Zipkin as part of the *same trace* — one root span for the incoming request, a client/server span pair for each downstream HTTP call, and a manually-created span for the shipping-cost calculation, all linked by one trace ID. That same trace ID appears in every log line the request produces, and the whole flow updates a couple of custom Micrometer counters and a timer along the way.

## Problem

"Add tracing" often means adding a dependency and trusting the auto-configuration — which mostly works, but two things about it are easy to get wrong without ever noticing in a demo: whether *your own* in-process logic (not just HTTP calls) actually gets a span nested correctly under the request, and whether your logs actually pick up the correlation IDs rather than just having empty placeholders in the log pattern. Both are worth actually checking against a real trace backend rather than trusting that wiring the dependency in was enough.

## Approach

- **`InternalServicesController`** stands in for "inventory" and "payments" as separate downstream services, called over real loopback HTTP from `OrderService` (the same self-call technique as `resilient-payment-client`'s `PaymentGatewayClient` — see `ServerPortHolder`'s javadoc) — this makes the resulting spans genuine auto-instrumented client/server pairs, not something faked in-process.
- **`OrderService.calculateShippingCost()`** creates and closes a span by hand via Micrometer Tracing's `Tracer` API, for logic that has nothing to auto-instrument — proving the manual-span path nests correctly under the same trace as the auto-instrumented HTTP spans, not just alongside them.
- **Custom Micrometer metrics** (`orders.placed`, `orders.failed`, `orders.processing.time`) are registered directly against `MeterRegistry` and exposed at `/actuator/prometheus`.
- **`logging.pattern.level`** includes `%X{traceId}`/`%X{spanId}` — Micrometer Tracing populates these via MDC automatically once the bridge is on the classpath, so any log statement inside a traced request picks up the correlation for free.

## What actually got verified, not just wired up

A single `POST /api/orders` produced this real trace in Zipkin — 6 spans, not asserted, pulled straight from `GET /api/v2/trace/{traceId}`:

```
http post /api/orders          (root, SERVER)
├─ http post -> /internal/inventory/reserve   (CLIENT)
│  └─ http post /internal/inventory/reserve   (SERVER)
├─ calculate-shipping-cost                     (manual span, no HTTP involved)
└─ http post -> /internal/payments/charge     (CLIENT)
   └─ http post /internal/payments/charge     (SERVER)
```

The same request's log lines all carried the identical trace ID the API response returned:

```
[observable-order-service,6a5f9a3ec3c59682faba90019852b170,faba90019852b170] ... Placing order ... for customer cust-2
[observable-order-service,6a5f9a3ec3c59682faba90019852b170,faba90019852b170] ... Inventory reserved for order ...
[observable-order-service,6a5f9a3ec3c59682faba90019852b170,faba90019852b170] ... Payment charged for order ...
```

And `/actuator/prometheus` showed `orders_placed_total 1.0` and a populated `orders_processing_time_seconds` timer after that one request — the custom instrumentation, not just the auto-instrumented HTTP spans, is actually recording real data.

## Architecture decisions

**The manual span had to be checked against a real backend to trust it nests correctly.** It would be easy to create a span via `tracer.nextSpan()` without ever confirming it lands as a *child* of the current request span rather than as an unrelated, disconnected trace. The Zipkin trace above shows `calculate-shipping-cost` sharing `parentId: 85c49ba47252b9b6` with the two client spans either side of it — genuinely nested, not just running at the same time.

**The "downstream services" are this same app calling itself.** Same trade-off as `resilient-payment-client`: a real system would call genuinely separate services, but a same-app loopback HTTP call (see `ServerPortHolder`) produces real client/server span pairs without needing a second deployable service or Testcontainers-across-multiple-images to demonstrate multi-hop tracing.

**Sampling probability is 1.0 — sample everything.** A real production service would sample a small percentage of requests (tracing every request at scale is expensive to store and mostly redundant signal). This project needs every request traced to be verifiable in a test and inspectable by hand; tuning the sampling rate down would be one of the first things to change before this pattern went into a real service.

## Stack

`Java 21` · `Spring Boot 3.4` · `Micrometer Tracing` (Brave bridge) · `Zipkin` · `Micrometer` + `Prometheus` registry · `Spring Boot Actuator` · `Spring RestClient` · `Maven` · `JUnit 5` / `AssertJ` / `Awaitility` · `Testcontainers`

## Running it

**1. Start Zipkin**

```powershell
docker compose up -d
```

**2. Build and run**

```powershell
.\mvnw.cmd -DskipTests package
java -jar target\observable-order-service.jar
```

**3. Place an order and look at the trace**

```powershell
curl -X POST http://localhost:8080/api/orders -H "Content-Type: application/json" `
  -d '{"customerId":"cust-1","items":["widget","gadget"],"amount":40.00}'
# -> {"orderId":"...","status":"PLACED","traceId":"..."}

curl "http://localhost:9411/api/v2/trace/<traceId>"
# -> the full 6-span trace shown above

curl http://localhost:8080/actuator/prometheus | grep orders_
```

Or open `http://localhost:9411` for Zipkin's own UI and search by service name `observable-order-service`.

**Tests**

```powershell
.\mvnw.cmd test "-Dtest=TracingIT"
```

`TracingIT` is excluded from plain `mvnw test` by Surefire's default `*IT` naming convention (it needs a real Zipkin container). It places an order through the real REST API, then polls Zipkin's own API for the resulting trace and asserts it actually contains multiple linked spans, including one named after the manual shipping-cost span.

## Status

- [x] Working end to end — verified manually against a real Zipkin container: the 6-span trace shown above, log lines carrying the matching trace ID, and populated custom metrics at `/actuator/prometheus`, all from one real request
- [x] `TracingIT` written — see CI badge above for its real pass/fail against a Testcontainers-managed Zipkin
- [x] README complete
- [ ] Demo/screenshot added
- [x] Pushed to GitHub

## Notes / next steps

- `TracingIT` could not be run locally in the sandbox this was built in — the same Testcontainers/Docker-Desktop-on-Windows npipe gap documented across this portfolio's other Java projects. Verified instead via a manual `docker compose up` + `curl` run against a real Zipkin container (the exact trace and log output above came from that run), and via CI, where GitHub's Ubuntu runners talk to Docker natively.
- No dashboards (Grafana) are included — `/actuator/prometheus` output is real and scrapeable, but this project stops at "the metrics exist and are correct," not "here's a dashboard for them," since building a dashboard doesn't exercise any new instrumentation logic.
- OpenTelemetry (rather than Brave) is the other common Micrometer Tracing bridge — swapping `micrometer-tracing-bridge-brave`/`zipkin-reporter-brave` for `micrometer-tracing-bridge-otel` and an OTLP exporter would be a reasonable next iteration if the target backend were Jaeger/Tempo/an OTLP collector instead of Zipkin specifically.
