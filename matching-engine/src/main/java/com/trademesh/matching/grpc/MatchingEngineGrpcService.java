package com.trademesh.matching.grpc;

import com.trademesh.grpc.matching.MatchingEngineServiceGrpc;
import com.trademesh.grpc.matching.SubmitOrderRequest;
import com.trademesh.grpc.matching.SubmitOrderResponse;
import com.trademesh.grpc.order.OrderStatus;
import com.trademesh.grpc.order.OrderStatusServiceGrpc;
import com.trademesh.grpc.order.UpdateOrderStatusRequest;
import com.trademesh.grpc.portfolio.PortfolioUpdateServiceGrpc;
import com.trademesh.grpc.portfolio.UpdateHoldingRequest;
import com.trademesh.matching.client.MarketDataClient;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.client.inject.GrpcClient;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

/**
 * KNOWN SIMPLIFICATION: this is not a real order book / matching algorithm.
 * Every incoming order is treated as "execute immediately in full at the
 * current market price" fetched from market-data-service — there is no
 * queuing, partial fills, or price-time priority. This is sufficient for a
 * demo but not representative of a real exchange matching engine.
 */
@GrpcService
public class MatchingEngineGrpcService extends MatchingEngineServiceGrpc.MatchingEngineServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(MatchingEngineGrpcService.class);

    private final MarketDataClient marketDataClient;

    @GrpcClient("order-service")
    private OrderStatusServiceGrpc.OrderStatusServiceBlockingStub orderStatusStub;

    @GrpcClient("portfolio-service")
    private PortfolioUpdateServiceGrpc.PortfolioUpdateServiceBlockingStub portfolioUpdateStub;

    public MatchingEngineGrpcService(MarketDataClient marketDataClient) {
        this.marketDataClient = marketDataClient;
    }

    @Override
    public void submitOrder(SubmitOrderRequest request, StreamObserver<SubmitOrderResponse> responseObserver) {
        try {
            BigDecimal marketPrice = marketDataClient.fetchCurrentPrice(request.getSymbol());

            notifyOrderService(request, marketPrice);
            notifyPortfolioService(request, marketPrice);

            responseObserver.onNext(SubmitOrderResponse.newBuilder()
                    .setAccepted(true)
                    .setMessage("Executed at market price " + marketPrice)
                    .build());
            responseObserver.onCompleted();
        } catch (Exception ex) {
            log.error("Failed to execute order {}: {}", request.getOrderId(), ex.getMessage(), ex);
            rejectOrder(request);
            responseObserver.onNext(SubmitOrderResponse.newBuilder()
                    .setAccepted(false)
                    .setMessage("Execution failed: " + ex.getMessage())
                    .build());
            responseObserver.onCompleted();
        }
    }

    private void notifyOrderService(SubmitOrderRequest request, BigDecimal marketPrice) {
        orderStatusStub.updateOrderStatus(UpdateOrderStatusRequest.newBuilder()
                .setOrderId(request.getOrderId())
                .setStatus(OrderStatus.FILLED)
                .setFilledQuantity(request.getQuantity())
                .setFillPrice(marketPrice.doubleValue())
                .build());
    }

    private void notifyPortfolioService(SubmitOrderRequest request, BigDecimal marketPrice) {
        double signedQuantity = request.getSide() == com.trademesh.grpc.matching.Side.SELL
                ? -request.getQuantity()
                : request.getQuantity();

        portfolioUpdateStub.updateHolding(UpdateHoldingRequest.newBuilder()
                .setUserId(request.getUserId())
                .setSymbol(request.getSymbol())
                .setQuantityDelta(signedQuantity)
                .setPrice(marketPrice.doubleValue())
                .build());
    }

    private void rejectOrder(SubmitOrderRequest request) {
        try {
            orderStatusStub.updateOrderStatus(UpdateOrderStatusRequest.newBuilder()
                    .setOrderId(request.getOrderId())
                    .setStatus(OrderStatus.REJECTED)
                    .setFilledQuantity(0)
                    .setFillPrice(0)
                    .build());
        } catch (Exception ex) {
            log.error("Failed to mark order {} as REJECTED: {}", request.getOrderId(), ex.getMessage(), ex);
        }
    }
}
