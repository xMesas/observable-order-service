package com.xmesas.observability.internal;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Stand-ins for "inventory" and "payments" as separate downstream services. Each call from
 * OrderService lands here over real HTTP, which is what makes the resulting spans genuine
 * auto-instrumented child spans rather than something manually faked — Spring Boot's
 * RestClient/RestTemplate instrumentation creates a client span, and this controller's own
 * request handling creates a server span, linked by the same trace ID.
 */
@RestController
@RequestMapping("/internal")
public class InternalServicesController {

    @PostMapping("/inventory/reserve")
    public Map<String, Object> reserveInventory(@RequestBody List<String> items) {
        sleep(30);
        return Map.of("reserved", true, "itemCount", items.size());
    }

    @PostMapping("/payments/charge")
    public Map<String, Object> chargePayment(@RequestBody BigDecimal amount) {
        sleep(50);
        return Map.of("charged", true, "amount", amount);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
