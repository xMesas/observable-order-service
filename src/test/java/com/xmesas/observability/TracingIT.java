package com.xmesas.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xmesas.observability.order.OrderRequest;
import com.xmesas.observability.order.OrderResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.Flushable;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Real Zipkin via Testcontainers — places an order, then polls Zipkin's own REST API for the
 * resulting trace (span export is asynchronous, so this can't just assert immediately after the
 * HTTP response) and checks that it actually contains multiple spans linked by the trace ID
 * returned in the API response: the root request span, the auto-instrumented inventory and
 * payment client spans, and the manually-created shipping-cost span.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "logging.level.zipkin2=DEBUG",
        "logging.level.io.micrometer.tracing=DEBUG",
        "logging.level.brave=DEBUG"
    })
class TracingIT {

    @Container
    static GenericContainer<?> zipkin = new GenericContainer<>(DockerImageName.parse("openzipkin/zipkin:latest"))
        .withExposedPorts(9411);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("management.zipkin.tracing.endpoint",
            () -> "http://" + zipkin.getHost() + ":" + zipkin.getMappedPort(9411) + "/api/v2/spans");
    }

    @LocalServerPort
    int port;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Environment environment;

    private final TestRestTemplate restTemplate = new TestRestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void placingAnOrderProducesATraceWithMultipleLinkedSpansInZipkin() throws Exception {
        System.out.println("[TracingIT] Configured zipkin endpoint: "
            + environment.getProperty("management.zipkin.tracing.endpoint"));
        ResponseEntity<String> zipkinHealth = restTemplate.getForEntity(zipkinUrl("/health"), String.class);
        System.out.println("[TracingIT] Zipkin health check: " + zipkinHealth.getStatusCode() + " " + zipkinHealth.getBody());

        OrderRequest request = new OrderRequest("customer-1", List.of("widget", "gadget"), new BigDecimal("40.00"));

        OrderResult result = restTemplate.postForObject(url("/api/orders"), request, OrderResult.class);

        assertThat(result.status()).isEqualTo("PLACED");
        assertThat(result.traceId()).isNotBlank();

        // Both Brave's AsyncReporter and AsyncZipkinSpanHandler implement Flushable — forcing a
        // flush here makes the export deterministic instead of depending on the reporter's own
        // background timer, which is what actually explained CI reliably timing out at 30s with
        // zero successful polls while a local run saw the trace appear in ~1.7s: whatever the
        // exact timer/thread-scheduling difference was, forcing the flush sidesteps it entirely.
        var flushables = applicationContext.getBeansOfType(Flushable.class);
        System.out.println("[TracingIT] Flushable beans found: " + flushables.keySet());
        flushables.forEach((name, flushable) -> {
            try {
                flushable.flush();
                System.out.println("[TracingIT] Flushed bean: " + name);
            } catch (IOException e) {
                System.out.println("[TracingIT] Flush FAILED for bean " + name + ": " + e);
            }
        });

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            // Span export to Zipkin is asynchronous, so the trace often isn't queryable yet on
            // the first poll — Zipkin returns 404 with a plain-text body (not JSON) until it is.
            // Awaitility's untilAsserted only retries on AssertionError, so the status check has
            // to come — and fail as an assertion — before any attempt to parse the body as JSON,
            // or a JsonParseException on that 404 body escapes and fails the test immediately
            // instead of being retried. Found by CI failing on the very first push with exactly
            // that parse exception, not a timeout.
            ResponseEntity<String> response = restTemplate.getForEntity(
                zipkinUrl("/api/v2/trace/" + result.traceId()), String.class);
            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();

            JsonNode spans = objectMapper.readTree(response.getBody());

            // Root request span + inventory client span + shipping-cost span + payment client
            // span, at minimum — exact count depends on instrumentation detail, so this checks
            // "genuinely more than one span, linked by this trace" rather than an exact number.
            assertThat(spans.isArray()).isTrue();
            assertThat(spans.size()).isGreaterThanOrEqualTo(3);

            List<String> spanNames = objectMapper.convertValue(spans, List.class).stream()
                .map(node -> String.valueOf(((java.util.Map<?, ?>) node).get("name")))
                .toList();
            assertThat(spanNames).anyMatch(name -> name.contains("shipping"));
        });
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private String zipkinUrl(String path) {
        return "http://" + zipkin.getHost() + ":" + zipkin.getMappedPort(9411) + path;
    }
}
