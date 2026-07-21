package com.xmesas.observability.config;

import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * OrderService calls this same app's own /internal/** endpoints over real HTTP — a deliberate
 * simplification (same technique as resilient-payment-client's PaymentGatewayClient) so this
 * project needs no second deployable service to demonstrate genuine auto-instrumented,
 * multi-hop distributed tracing. That only works if the client knows the actual bound port,
 * which isn't known at bean-creation time when running with a random port (as tests do) — this
 * captures it the moment the embedded server actually binds one.
 */
@Component
public class ServerPortHolder implements ApplicationListener<WebServerInitializedEvent> {

    private volatile int port = -1;

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        this.port = event.getWebServer().getPort();
    }

    public int getPort() {
        return port;
    }
}
