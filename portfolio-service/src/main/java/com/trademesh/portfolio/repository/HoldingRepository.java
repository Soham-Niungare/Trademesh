package com.trademesh.portfolio.repository;

import com.trademesh.portfolio.entity.Holding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HoldingRepository extends JpaRepository<Holding, UUID> {

    List<Holding> findByUserId(UUID userId);

    Optional<Holding> findByUserIdAndSymbol(UUID userId, String symbol);
}
