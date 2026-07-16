package com.karadas.l7defense.orders_service.item;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ItemRepository extends JpaRepository<Item, Long> {



    @Modifying
    @Query("UPDATE Item i SET i.stockQuantity = i.stockQuantity - :quantity " +
            "WHERE i.id = :itemId AND i.stockQuantity >= :quantity")
    int decrementStock(@Param("itemId") Long itemId, @Param("quantity") Integer quantity);
}