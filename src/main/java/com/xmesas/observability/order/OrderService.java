package com.xmesas.observability.order;

import com.xmesas.observability.config.ServerPortHolder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Two different kinds of span in one flow, deliberately: the inventory and payment calls get
 * auto-instrumented client/server spans for free from Spring Boot's RestClient instrumentation,
 * while shipping-cost calculation is pure in-process logic with nothing to auto-instrument —
 * that one gets a span created and closed by hand via the Tracer API, still correctly nested
 * under the same trace. Both show up as children of the same root span in Zipkin.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final RestClient.Builder restClientBuilder;
    private final ServerPortHolder serverPortHolder;
    private final Tracer tracer;
    private final Counter ordersPlacedCounter;
    private final Counter ordersFailedCounter;
    private final Timer orderProcessingTimer;
    private volatile RestClient restClient;

    public OrderService(RestClient.Builder restClientBuilder, ServerPortHolder serverPortHolder,
                         Tracer tracer, MeterRegistry meterRegistry) {
        this.restClientBuilder = restClientBuilder;
        this.serverPortHolder = serverPortHolder;
        this.tracer = tracer;
        this.ordersPlacedCounter = Counter.builder("orders.placed")
            .description("Orders successfully placed")
            .register(meterRegistry);
        this.ordersFailedCounter = Counter.builder("orders.failed")
            .description("Orders that failed during placement")
            .register(meterRegistry);
        this.orderProcessingTimer = Timer.builder("orders.processing.time")
            .description("End-to-end time to place an order, including downstream calls")
            .register(meterRegistry);
    }

    public OrderResult placeOrder(OrderRequest request) {
        return orderProcessingTimer.record(() -> {
            try {
                String orderId = UUID.randomUUID().toString();
                log.info("Placing order {} for customer {}", orderId, request.customerId());

                client().post().uri("/internal/inventory/reserve").body(request.items()).retrieve().toBodilessEntity();
                log.info("Inventory reserved for order {}", orderId);

                calculateShippingCost(request.amount());

                client().post().uri("/internal/payments/charge").body(request.amount()).retrieve().toBodilessEntity();
                log.info("Payment charged for order {}", orderId);

                ordersPlacedCounter.increment();

                Span currentSpan = tracer.currentSpan();
                String traceId = currentSpan != null ? currentSpan.context().traceId() : "unknown";
                Boolean sampled = currentSpan != null ? currentSpan.context().sampled() : null;
                log.info("Order {} trace sampled = {}", orderId, sampled);
                return new OrderResult(orderId, "PLACED", traceId);
            } catch (RuntimeException e) {
                ordersFailedCounter.increment();
                throw e;
            }
        });
    }

    private BigDecimal calculateShippingCost(BigDecimal orderAmount) {
        Span span = tracer.nextSpan().name("calculate-shipping-cost").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            sleep(10); // pretend this is a nontrivial calculation worth its own span
            return orderAmount.multiply(new BigDecimal("0.05"));
        } finally {
            span.end();
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Same lazy-build-once pattern as resilient-payment-client's PaymentGatewayClient, and for
    // the same reason: the port isn't known until the embedded server actually binds one.
    private RestClient client() {
        RestClient existing = restClient;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (restClient == null) {
                restClient = restClientBuilder.baseUrl("http://localhost:" + serverPortHolder.getPort()).build();
            }
            return restClient;
        }
    }
}
