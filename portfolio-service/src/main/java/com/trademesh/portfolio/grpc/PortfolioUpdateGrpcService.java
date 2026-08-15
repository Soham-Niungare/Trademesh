package com.trademesh.portfolio.grpc;

import com.trademesh.grpc.portfolio.PortfolioUpdateServiceGrpc;
import com.trademesh.grpc.portfolio.UpdateHoldingRequest;
import com.trademesh.grpc.portfolio.UpdateHoldingResponse;
import com.trademesh.portfolio.service.HoldingService;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.UUID;

@GrpcService
public class PortfolioUpdateGrpcService extends PortfolioUpdateServiceGrpc.PortfolioUpdateServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(PortfolioUpdateGrpcService.class);

    private final HoldingService holdingService;

    public PortfolioUpdateGrpcService(HoldingService holdingService) {
        this.holdingService = holdingService;
    }

    @Override
    public void updateHolding(UpdateHoldingRequest request, StreamObserver<UpdateHoldingResponse> responseObserver) {
        UUID userId = UUID.fromString(request.getUserId());
        BigDecimal quantityDelta = BigDecimal.valueOf(request.getQuantityDelta());
        BigDecimal price = BigDecimal.valueOf(request.getPrice());

        holdingService.applyFill(userId, request.getSymbol(), quantityDelta, price);
        log.info("Applied holding update for user {} symbol {} delta {} at price {}",
                userId, request.getSymbol(), quantityDelta, price);

        responseObserver.onNext(UpdateHoldingResponse.newBuilder().setAcknowledged(true).build());
        responseObserver.onCompleted();
    }
}
