package com.trademesh.backend.controller;

import com.trademesh.backend.service.MarketDataService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Backed entirely by the Redis snapshot ({@link MarketDataService#getSnapshot})
 * -- never the in-memory engine or Postgres directly. A symbol with no resting
 * orders (or that's never traded at all) returns an empty book, not a 404;
 * symbols aren't a registered/known set in this system.
 */
@RestController
@RequestMapping("/api/market")
public class MarketController {

    private final MarketDataService marketDataService;

    public MarketController(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    @GetMapping("/{symbol}/orderbook")
    public OrderBookResponse getOrderBook(@PathVariable String symbol) {
        return OrderBookResponse.from(marketDataService.getSnapshot(symbol));
    }
}
