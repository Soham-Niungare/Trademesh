package com.trademesh.backend.service;

import com.trademesh.backend.entity.Order;
import com.trademesh.backend.entity.OrderStatus;
import com.trademesh.backend.repository.OrderRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/** On startup, rebuilds every symbol's in-memory book from whatever is still resting in Postgres. */
@Component
public class OrderBookWarmupRunner implements ApplicationRunner {

    private static final List<OrderStatus> RESTING_STATUSES = List.of(OrderStatus.OPEN, OrderStatus.PARTIALLY_FILLED);

    private final OrderRepository orderRepository;
    private final OrderBookRegistry orderBookRegistry;
    private final OrderBookLoader orderBookLoader;

    public OrderBookWarmupRunner(OrderRepository orderRepository, OrderBookRegistry orderBookRegistry,
                                  OrderBookLoader orderBookLoader) {
        this.orderRepository = orderRepository;
        this.orderBookRegistry = orderBookRegistry;
        this.orderBookLoader = orderBookLoader;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Order> restingOrders = orderRepository.findByStatusInOrderByCreatedAtAsc(RESTING_STATUSES);
        orderBookLoader.reload(restingOrders, orderBookRegistry);
    }
}
