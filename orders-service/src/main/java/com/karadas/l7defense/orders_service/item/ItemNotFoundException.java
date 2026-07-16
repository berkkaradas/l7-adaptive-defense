package com.karadas.l7defense.orders_service.item;

public class ItemNotFoundException extends RuntimeException {
    public ItemNotFoundException(Long itemId) {
        super("Item not found: " + itemId);
    }
}