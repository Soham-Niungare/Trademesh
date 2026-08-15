package com.trademesh.backend.repository;

import com.trademesh.backend.entity.Order;
import com.trademesh.backend.entity.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    List<Order> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** Used on startup to rebuild the in-memory book; ordering preserves time priority. */
    List<Order> findByStatusInOrderByCreatedAtAsc(Collection<OrderStatus> statuses);
}
