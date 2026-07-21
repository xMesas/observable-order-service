package com.xmesas.observability.order;

public record OrderResult(String orderId, String status, String traceId) {
}
