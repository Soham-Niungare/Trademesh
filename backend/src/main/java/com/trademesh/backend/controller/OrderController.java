package com.trademesh.backend.controller;

import com.trademesh.backend.entity.Order;
import com.trademesh.backend.exception.OrderNotCancellableException;
import com.trademesh.backend.exception.OrderNotFoundException;
import com.trademesh.backend.repository.OrderRepository;
import com.trademesh.backend.repository.TradeRepository;
import com.trademesh.backend.service.SubmitOrderRequest;
import com.trademesh.backend.service.SubmitOrderResult;
import com.trademesh.backend.service.TradeService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Every route here requires authentication (enforced by SecurityConfig).
 * {@code userId} always comes from {@code @AuthenticationPrincipal} -- the JWT
 * subject set by JwtAuthenticationFilter -- never from a request body, so a
 * caller can never place or touch an order as someone else.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final TradeService tradeService;
    private final OrderRepository orderRepository;
    private final TradeRepository tradeRepository;

    public OrderController(TradeService tradeService, OrderRepository orderRepository,
                            TradeRepository tradeRepository) {
        this.tradeService = tradeService;
        this.orderRepository = orderRepository;
        this.tradeRepository = tradeRepository;
    }

    @PostMapping
    public ResponseEntity<OrderSubmissionResponse> submitOrder(@AuthenticationPrincipal UUID userId,
                                                                @Valid @RequestBody CreateOrderRequest request) {
        SubmitOrderRequest submitRequest = new SubmitOrderRequest(userId, request.symbol(), request.side(),
                request.type(), request.price(), request.quantity());
        SubmitOrderResult result = tradeService.submitOrder(submitRequest);

        Order order = orderRepository.findById(result.orderId()).orElseThrow();
        List<TradeResponse> trades = tradeRepository.findByOrderId(result.orderId()).stream()
                .map(TradeResponse::from)
                .toList();

        OrderSubmissionResponse body = new OrderSubmissionResponse(OrderResponse.from(order), trades);
        return ResponseEntity.created(URI.create("/api/orders/" + order.getId())).body(body);
    }

    @GetMapping
    public List<OrderResponse> listOrders(@AuthenticationPrincipal UUID userId) {
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(OrderResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public OrderResponse getOrder(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        return OrderResponse.from(findOwnedOrder(userId, id));
    }

    @DeleteMapping("/{id}")
    public OrderResponse cancelOrder(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        findOwnedOrder(userId, id); // 404 before we even ask the engine, if it's not this caller's order

        if (!tradeService.cancelOrder(id)) {
            throw new OrderNotCancellableException("Order " + id + " is not open for cancellation");
        }

        return OrderResponse.from(orderRepository.findById(id).orElseThrow());
    }

    /** 404 for both "doesn't exist" and "exists but isn't yours" -- a non-owner can't tell the two apart. */
    private Order findOwnedOrder(UUID userId, UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order " + orderId + " not found"));
        if (!order.getUserId().equals(userId)) {
            throw new OrderNotFoundException("Order " + orderId + " not found");
        }
        return order;
    }
}
