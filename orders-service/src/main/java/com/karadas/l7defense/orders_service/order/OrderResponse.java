package com.karadas.l7defense.orders_service.order;

import java.math.BigDecimal;

public record OrderResponse(Long id, String itemName, BigDecimal itemPrice, Integer quantity, Long memberId) {}