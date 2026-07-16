package com.karadas.l7defense.orders_service.order;

import com.karadas.l7defense.orders_service.item.Item;
import jakarta.persistence.*;

@Entity
@Table(name = "orders", indexes = {
        @Index(name = "idx_orders_member_id", columnList = "memberId")
})
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "item_id", nullable = false)
    private Item item;

    @Column(nullable = false)
    private Integer quantity;

    @Column(nullable = false)
    private Long memberId;

    protected Order() {
    }

    public Order(Item item, Integer quantity, Long memberId) {
        this.item = item;
        this.quantity = quantity;
        this.memberId = memberId;
    }

    public Long getId() { return id; }
    public Item getItem() { return item; }
    public Integer getQuantity() { return quantity; }
    public Long getMemberId() { return memberId; }
}