package com.trademesh.portfolio.service;

import com.trademesh.portfolio.dto.HoldingResponse;
import com.trademesh.portfolio.entity.Holding;
import com.trademesh.portfolio.repository.HoldingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class HoldingService {

    private final HoldingRepository holdingRepository;

    public HoldingService(HoldingRepository holdingRepository) {
        this.holdingRepository = holdingRepository;
    }

    /**
     * Applies a fill to a user's holding. Buys (positive delta) roll the
     * fill price into a weighted-average cost basis; sells (negative delta)
     * reduce quantity but leave the existing average cost basis untouched,
     * matching standard weighted-average-cost accounting.
     */
    @Transactional
    public void applyFill(UUID userId, String symbol, BigDecimal quantityDelta, BigDecimal price) {
        Holding holding = holdingRepository.findByUserIdAndSymbol(userId, symbol)
                .orElseGet(() -> {
                    Holding created = new Holding();
                    created.setUserId(userId);
                    created.setSymbol(symbol);
                    created.setQuantity(BigDecimal.ZERO);
                    created.setAvgPrice(BigDecimal.ZERO);
                    return created;
                });

        BigDecimal currentQuantity = holding.getQuantity();
        BigDecimal newQuantity = currentQuantity.add(quantityDelta);

        if (quantityDelta.signum() > 0) {
            BigDecimal currentCost = currentQuantity.multiply(holding.getAvgPrice());
            BigDecimal addedCost = quantityDelta.multiply(price);
            BigDecimal newAvgPrice = newQuantity.signum() == 0
                    ? BigDecimal.ZERO
                    : currentCost.add(addedCost).divide(newQuantity, 4, RoundingMode.HALF_UP);
            holding.setAvgPrice(newAvgPrice);
        }
        // Sells don't change the average cost basis of the remaining shares.

        holding.setQuantity(newQuantity);
        holding.setUpdatedAt(Instant.now());
        holdingRepository.save(holding);
    }

    public List<HoldingResponse> getHoldings(UUID userId) {
        return holdingRepository.findByUserId(userId).stream()
                .map(HoldingResponse::from)
                .toList();
    }
}
