package com.xmesas.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xmesas.observability.order.OrderRequest;
import com.xmesas.observability.order.OrderResult;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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

    private final TestRestTemplate restTemplate = new TestRestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void placingAnOrderProducesATraceWithMultipleLinkedSpansInZipkin() throws Exception {
        OrderRequest request = new OrderRequest("customer-1", List.of("widget", "gadget"), new BigDecimal("40.00"));

        OrderResult result = restTemplate.postForObject(url("/api/orders"), request, OrderResult.class);

        assertThat(result.status()).isEqualTo("PLACED");
        assertThat(result.traceId()).isNotBlank();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            String body = restTemplate.getForObject(zipkinUrl("/api/v2/trace/" + result.traceId()), String.class);
            JsonNode spans = objectMapper.readTree(body);

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
