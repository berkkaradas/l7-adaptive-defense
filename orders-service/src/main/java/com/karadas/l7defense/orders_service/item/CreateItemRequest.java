package com.karadas.l7defense.orders_service.item;

import java.math.BigDecimal;

public record CreateItemRequest(String name, BigDecimal price, Integer stockQuantity) {}