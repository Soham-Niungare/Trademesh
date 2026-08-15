package com.trademesh.marketdata.controller;

import com.trademesh.marketdata.model.Price;
import com.trademesh.marketdata.service.PriceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/market")
public class MarketDataController {

    private final PriceService priceService;

    public MarketDataController(PriceService priceService) {
        this.priceService = priceService;
    }

    @GetMapping("/prices")
    public ResponseEntity<List<Price>> getAllPrices() {
        return ResponseEntity.ok(priceService.getAllPrices());
    }

    @GetMapping("/prices/{symbol}")
    public ResponseEntity<Price> getPrice(@PathVariable String symbol) {
        return ResponseEntity.ok(priceService.getPrice(symbol));
    }
}
