package com.trademesh.portfolio.controller;

import com.trademesh.portfolio.dto.HoldingResponse;
import com.trademesh.portfolio.service.HoldingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/portfolio")
public class PortfolioController {

    private final HoldingService holdingService;

    public PortfolioController(HoldingService holdingService) {
        this.holdingService = holdingService;
    }

    @GetMapping("/{userId}")
    public ResponseEntity<List<HoldingResponse>> getPortfolio(@PathVariable UUID userId) {
        return ResponseEntity.ok(holdingService.getHoldings(userId));
    }
}
