package com.trademesh.order.grpc;

import com.trademesh.grpc.matching.MatchingEngineServiceGrpc;
import com.trademesh.grpc.matching.Side;
import com.trademesh.grpc.matching.SubmitOrderRequest;
import com.trademesh.grpc.matching.SubmitOrderResponse;
import com.trademesh.order.entity.Order;
import io.grpc.StatusRuntimeException;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class MatchingEngineClient {

    private static final Logger log = LoggerFactory.getLogger(MatchingEngineClient.class);

    @GrpcClient("matching-engine")
    private MatchingEngineServiceGrpc.MatchingEngineServiceBlockingStub matchingEngineStub;

    /**
     * Fire-and-forget dispatch: the REST caller already got a PENDING order
     * back, the eventual FILLED/REJECTED transition arrives later via the
     * OrderStatusService gRPC callback.
     */
    @Async
    public void submitOrder(Order order) {
        SubmitOrderRequest request = SubmitOrderRequest.newBuilder()
                .setOrderId(order.getId().toString())
                .setUserId(order.getUserId().toString())
                .setSymbol(order.getSymbol())
                .setSide(order.getSide() == com.trademesh.order.entity.OrderSide.BUY ? Side.BUY : Side.SELL)
                .setQuantity(order.getQuantity().doubleValue())
                .setPrice(order.getPrice().doubleValue())
                .build();

        try {
            SubmitOrderResponse response = matchingEngineStub.submitOrder(request);
            log.info("Order {} submitted to matching engine: accepted={} message={}",
                    order.getId(), response.getAccepted(), response.getMessage());
        } catch (StatusRuntimeException ex) {
            log.error("Failed to submit order {} to matching engine: {}", order.getId(), ex.getStatus(), ex);
        }
    }
}
