package com.trademesh.matching.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Deliberately a plain JDK HttpClient rather than a full web/reactive
 * starter: matching-engine has no other REST needs, so pulling in
 * spring-webflux or RestTemplate for one GET call would be unnecessary
 * weight on a gRPC-only, web-application-type=none service.
 */
@Component
public class MarketDataClient {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;

    public MarketDataClient(@Value("${trademesh.market-data.base-url}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public BigDecimal fetchCurrentPrice(String symbol) throws IOException, InterruptedException {
        URI uri = URI.create(baseUrl + "/market/prices/" + symbol);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("market-data-service returned status " + response.statusCode() + " for symbol " + symbol);
        }

        JsonNode json = objectMapper.readTree(response.body());
        return new BigDecimal(json.get("price").asText());
    }
}
