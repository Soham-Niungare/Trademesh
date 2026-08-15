package com.trademesh.backend.service;

import com.trademesh.backend.engine.OrderBook;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Owns the one {@link OrderBook} per symbol that Phase 2 anticipated a later phase would need. */
@Component
public class OrderBookRegistry {

    private final Map<String, OrderBook> books = new ConcurrentHashMap<>();

    public OrderBook getOrCreate(String symbol) {
        return books.computeIfAbsent(symbol, OrderBook::new);
    }
}
