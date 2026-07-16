package com.karadas.l7defense.orders_service.item;

import java.math.BigDecimal;

public record ItemResponse(Long id, String name, BigDecimal price, Integer stockQuantity) {}