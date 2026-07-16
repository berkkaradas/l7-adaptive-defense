package com.karadas.l7defense.orders_service.order;

import com.karadas.l7defense.orders_service.item.Item;
import com.karadas.l7defense.orders_service.item.ItemNotFoundException;
import com.karadas.l7defense.orders_service.item.ItemRepository;
import com.karadas.l7defense.orders_service.item.InsufficientStockException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ItemRepository itemRepository;

    public OrderService(OrderRepository orderRepository, ItemRepository itemRepository) {
        this.orderRepository = orderRepository;
        this.itemRepository = itemRepository;
    }
    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {
        Item item = itemRepository.findById(request.itemId())
                .orElseThrow(() -> new ItemNotFoundException(request.itemId()));

        int updatedRows = itemRepository.decrementStock(request.itemId(), request.quantity());
        if (updatedRows == 0) {
            throw new InsufficientStockException(item.getId(), item.getStockQuantity(), request.quantity());
        }

        Order order = new Order(item, request.quantity(), request.memberId());
        Order saved = orderRepository.save(order);

        return new OrderResponse(saved.getId(), item.getName(), item.getPrice(), saved.getQuantity(), saved.getMemberId());
    }
    public List<OrderResponse> getOrdersForMember(Long memberId) {
        return orderRepository.findByMemberIdWithItem(memberId).stream()
                .map(this::toResponse)
                .toList();
    }

    public java.util.Optional<OrderResponse> getOrderForMember(Long id, Long memberId) {
        return orderRepository.findByIdAndMemberId(id, memberId)
                .map(this::toResponse);
    }

    private OrderResponse toResponse(Order order) {
        return new OrderResponse(order.getId(), order.getItem().getName(), order.getItem().getPrice(),
                order.getQuantity(), order.getMemberId());
    }


}