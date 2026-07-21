package com.xmesas.observability.order;

import java.math.BigDecimal;
import java.util.List;

public record OrderRequest(String customerId, List<String> items, BigDecimal amount) {
}
