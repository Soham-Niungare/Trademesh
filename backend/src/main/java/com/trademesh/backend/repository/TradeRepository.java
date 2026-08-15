package com.trademesh.backend.repository;

import com.trademesh.backend.entity.Trade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface TradeRepository extends JpaRepository<Trade, UUID> {

    List<Trade> findBySymbolOrderByExecutedAtDesc(String symbol);

    @Query("SELECT t FROM Trade t WHERE t.buyOrderId = :orderId OR t.sellOrderId = :orderId")
    List<Trade> findByOrderId(@Param("orderId") UUID orderId);
}
