package com.trademesh.backend.controller;

import java.util.List;

public record OrderSubmissionResponse(OrderResponse order, List<TradeResponse> trades) {
}
