package com.karadas.l7defense.orders_service.item;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ItemService {

    private final ItemRepository itemRepository;

    public ItemService(ItemRepository itemRepository) {
        this.itemRepository = itemRepository;
    }
    @Transactional
    public ItemResponse createItem(CreateItemRequest request) {
        Item item = new Item(request.name(), request.price(), request.stockQuantity());
        Item saved = itemRepository.save(item);
        return toResponse(saved);
    }

    public java.util.List<ItemResponse> getAllItems() {
        return itemRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    public ItemResponse getItemById(Long id) {
        Item item = itemRepository.findById(id)
                .orElseThrow(() -> new ItemNotFoundException(id));
        return toResponse(item);
    }

    private ItemResponse toResponse(Item item) {
        return new ItemResponse(item.getId(), item.getName(), item.getPrice(), item.getStockQuantity());
    }
}