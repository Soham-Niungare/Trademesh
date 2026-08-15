package com.trademesh.order.grpc;

import com.trademesh.grpc.order.OrderStatusServiceGrpc;
import com.trademesh.grpc.order.UpdateOrderStatusRequest;
import com.trademesh.grpc.order.UpdateOrderStatusResponse;
import com.trademesh.order.entity.Order;
import com.trademesh.order.entity.OrderStatus;
import com.trademesh.order.repository.OrderRepository;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@GrpcService
public class OrderStatusGrpcService extends OrderStatusServiceGrpc.OrderStatusServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(OrderStatusGrpcService.class);

    private final OrderRepository orderRepository;

    public OrderStatusGrpcService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    public void updateOrderStatus(UpdateOrderStatusRequest request, StreamObserver<UpdateOrderStatusResponse> responseObserver) {
        UUID orderId = UUID.fromString(request.getOrderId());

        orderRepository.findById(orderId).ifPresentOrElse(order -> {
            order.setStatus(mapStatus(request.getStatus()));
            order.setFilledQuantity(BigDecimal.valueOf(request.getFilledQuantity()));
            order.setFillPrice(BigDecimal.valueOf(request.getFillPrice()));
            order.setUpdatedAt(Instant.now());
            orderRepository.save(order);
            log.info("Order {} updated to status {}", orderId, order.getStatus());
        }, () -> log.warn("Received status update for unknown order {}", orderId));

        UpdateOrderStatusResponse response = UpdateOrderStatusResponse.newBuilder()
                .setAcknowledged(true)
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    private OrderStatus mapStatus(com.trademesh.grpc.order.OrderStatus grpcStatus) {
        return switch (grpcStatus) {
            case FILLED -> OrderStatus.FILLED;
            case REJECTED -> OrderStatus.REJECTED;
            case CANCELLED -> OrderStatus.CANCELLED;
            case PENDING, ORDER_STATUS_UNSPECIFIED, UNRECOGNIZED -> OrderStatus.PENDING;
        };
    }
}
