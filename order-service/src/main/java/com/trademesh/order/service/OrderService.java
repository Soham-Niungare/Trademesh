package com.trademesh.order.service;

import com.trademesh.order.dto.CreateOrderRequest;
import com.trademesh.order.dto.OrderResponse;
import com.trademesh.order.entity.Order;
import com.trademesh.order.entity.OrderStatus;
import com.trademesh.order.exception.OrderNotFoundException;
import com.trademesh.order.grpc.MatchingEngineClient;
import com.trademesh.order.repository.OrderRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final MatchingEngineClient matchingEngineClient;

    public OrderService(OrderRepository orderRepository, MatchingEngineClient matchingEngineClient) {
        this.orderRepository = orderRepository;
        this.matchingEngineClient = matchingEngineClient;
    }

    // Deliberately not @Transactional: JpaRepository.save() already commits
    // on its own, and the async dispatch to matching-engine below must only
    // fire *after* that commit. Matching-engine's callback can complete a
    // full round trip (fetch price, call back to mark FILLED) fast enough
    // to race an outer transaction that hadn't committed the INSERT yet,
    // making the order briefly invisible to the callback's lookup.
    public OrderResponse createOrder(CreateOrderRequest request) {
        Order order = new Order();
        order.setUserId(request.userId());
        order.setSymbol(request.symbol());
        order.setSide(request.side());
        order.setQuantity(request.quantity());
        order.setPrice(request.price());
        order.setType(request.type());
        order.setStatus(OrderStatus.PENDING);
        order = orderRepository.save(order);

        matchingEngineClient.submitOrder(order);

        return OrderResponse.from(order);
    }

    public OrderResponse getOrder(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        return OrderResponse.from(order);
    }

    public List<OrderResponse> getOrdersByUser(UUID userId) {
        return orderRepository.findByUserId(userId).stream()
                .map(OrderResponse::from)
                .toList();
    }

    public List<OrderResponse> getAllOrders() {
        return orderRepository.findAll().stream()
                .map(OrderResponse::from)
                .toList();
    }
}
