package com.trademesh.marketdata.service;

import com.trademesh.marketdata.exception.SymbolNotFoundException;
import com.trademesh.marketdata.model.Price;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class PriceService {

    // Simulated in-memory feed, no persistence: seed prices for the five
    // demo symbols and let the scheduled job below randomly walk them.
    private static final Map<String, BigDecimal> SEED_PRICES = Map.of(
            "TMSH", new BigDecimal("142.50"),
            "ACME", new BigDecimal("58.20"),
            "GLBX", new BigDecimal("301.75"),
            "NOVA", new BigDecimal("19.90"),
            "ORBT", new BigDecimal("87.40")
    );

    private static final BigDecimal MAX_DRIFT_PCT = new BigDecimal("0.02");
    private static final BigDecimal MIN_PRICE = new BigDecimal("0.01");

    private final Map<String, Price> prices = new ConcurrentHashMap<>();

    public PriceService() {
        Instant now = Instant.now();
        SEED_PRICES.forEach((symbol, price) -> prices.put(symbol, new Price(symbol, price, now)));
    }

    @Scheduled(fixedRate = 3000)
    public void driftPrices() {
        prices.replaceAll((symbol, current) -> {
            BigDecimal driftPct = randomDrift();
            BigDecimal next = current.price()
                    .multiply(BigDecimal.ONE.add(driftPct))
                    .setScale(2, RoundingMode.HALF_UP);
            if (next.compareTo(MIN_PRICE) < 0) {
                next = MIN_PRICE;
            }
            return new Price(symbol, next, Instant.now());
        });
    }

    private BigDecimal randomDrift() {
        double drift = ThreadLocalRandom.current().nextDouble(-1, 1) * MAX_DRIFT_PCT.doubleValue();
        return BigDecimal.valueOf(drift);
    }

    public List<Price> getAllPrices() {
        return List.copyOf(prices.values());
    }

    public Price getPrice(String symbol) {
        Price price = prices.get(symbol.toUpperCase());
        if (price == null) {
            throw new SymbolNotFoundException(symbol);
        }
        return price;
    }
}
