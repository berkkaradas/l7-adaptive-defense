package com.karadas.l7defense.orders_service.order;

public record CreateOrderRequest(Long itemId, Integer quantity, Long memberId) {}