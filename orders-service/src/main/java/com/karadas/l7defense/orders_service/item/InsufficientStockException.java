package com.karadas.l7defense.orders_service.item;

public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(Long itemId, Integer available, Integer requested) {
        super("Insufficient stock for item " + itemId + ": available=" + available + ", requested=" + requested);
    }
}