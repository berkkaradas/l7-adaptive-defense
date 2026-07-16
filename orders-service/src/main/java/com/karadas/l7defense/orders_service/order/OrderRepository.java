package com.karadas.l7defense.orders_service.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    @Query("SELECT o FROM Order o JOIN FETCH o.item WHERE o.memberId = :memberId")
    List<Order> findByMemberIdWithItem(@Param("memberId") Long memberId);

    Optional<Order> findByIdAndMemberId(Long id, Long memberId);
}