package com.trademesh.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trademesh.backend.entity.Order;
import com.trademesh.backend.entity.OrderSide;
import com.trademesh.backend.entity.OrderType;
import com.trademesh.backend.repository.OrderRepository;
import com.trademesh.backend.repository.TradeRepository;
import com.trademesh.backend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class OrderControllerTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TradeRepository tradeRepository;

    @AfterEach
    void cleanUp() {
        tradeRepository.deleteAll();
        orderRepository.deleteAll();
        userRepository.deleteAll();
    }

    private static String uniqueSymbol() {
        return "SYM-" + UUID.randomUUID();
    }

    private String registerAndLogin(String username) throws Exception {
        String password = "password123";
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest(username, username + "@example.com", password))))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, password))))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readValue(result.getResponse().getContentAsString(), LoginResponse.class).token();
    }

    private UUID submitOrder(String token, String symbol, String side, String type, String price, String quantity) throws Exception {
        String body = """
                {"symbol":"%s","side":"%s","type":"%s","price":%s,"quantity":%s}
                """.formatted(symbol, side, type, price, quantity);

        MvcResult result = mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("order").get("id").asText());
    }

    @Test
    void submitOrder_withoutToken_returns401() throws Exception {
        String body = objectMapper.writeValueAsString(
                new CreateOrderRequest(uniqueSymbol(), OrderSide.BUY, OrderType.LIMIT, new BigDecimal("100"), new BigDecimal("10")));

        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void submitOrder_withToken_usesUserIdFromTokenNotRequestBody() throws Exception {
        String token = registerAndLogin("trader-" + UUID.randomUUID());
        UUID spoofedUserId = UUID.randomUUID();

        // "userId" isn't a field CreateOrderRequest declares -- prove it's actually
        // ignored (order still created, owned by the real caller) rather than causing
        // a deserialization failure or silently being trusted.
        String body = """
                {"userId":"%s","symbol":"%s","side":"BUY","type":"LIMIT","price":100,"quantity":10}
                """.formatted(spoofedUserId, uniqueSymbol());

        MvcResult result = mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode order = objectMapper.readTree(result.getResponse().getContentAsString()).get("order");
        UUID actualOrderId = UUID.fromString(order.get("id").asText());
        UUID actualUserId = UUID.fromString(order.get("userId").asText());

        assertNotEquals(spoofedUserId, actualUserId);

        Order persisted = orderRepository.findById(actualOrderId).orElseThrow();
        assertNotEquals(spoofedUserId, persisted.getUserId());
    }

    @Test
    void listOrders_returnsOwnOrdersMostRecentFirst() throws Exception {
        String token = registerAndLogin("lister-" + UUID.randomUUID());
        String symbol = uniqueSymbol();

        UUID firstOrderId = submitOrder(token, symbol, "BUY", "LIMIT", "10", "1");
        UUID secondOrderId = submitOrder(token, symbol, "BUY", "LIMIT", "11", "1");

        mockMvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(secondOrderId.toString()))
                .andExpect(jsonPath("$[1].id").value(firstOrderId.toString()));
    }

    @Test
    void getOrder_belongingToAnotherUser_returns404() throws Exception {
        String ownerToken = registerAndLogin("owner-" + UUID.randomUUID());
        String otherToken = registerAndLogin("other-" + UUID.randomUUID());

        UUID orderId = submitOrder(ownerToken, uniqueSymbol(), "BUY", "LIMIT", "100", "10");

        mockMvc.perform(get("/api/orders/" + orderId).header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancelOrder_belongingToAnotherUser_returns404() throws Exception {
        String ownerToken = registerAndLogin("owner-" + UUID.randomUUID());
        String otherToken = registerAndLogin("other-" + UUID.randomUUID());

        UUID orderId = submitOrder(ownerToken, uniqueSymbol(), "SELL", "LIMIT", "100", "10");

        mockMvc.perform(delete("/api/orders/" + orderId).header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancelOrder_alreadyFilled_returns409() throws Exception {
        String token = registerAndLogin("trader-" + UUID.randomUUID());
        String symbol = uniqueSymbol();

        UUID sellOrderId = submitOrder(token, symbol, "SELL", "LIMIT", "100", "10");
        submitOrder(token, symbol, "BUY", "LIMIT", "100", "10"); // fully matches the sell above

        mockMvc.perform(delete("/api/orders/" + sellOrderId).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isConflict());
    }

    /**
     * Regression: price carried no validation annotation at all (quantity had
     * {@code @NotNull @Positive}, price had nothing), and nothing downstream
     * checked its sign either -- EngineOrder only rejects a null price on a LIMIT
     * order, and OrderBook.submitOrder only checks the symbol. A negative-priced
     * LIMIT order was accepted with 201 and rested in the book, corrupting the
     * depth ladder and the best bid/ask derived from it.
     */
    @Test
    void submitOrder_negativePrice_returns400AndPersistsNothing() throws Exception {
        String token = registerAndLogin("neg-price-" + UUID.randomUUID());
        String body = """
                {"symbol":"%s","side":"BUY","type":"LIMIT","price":-100,"quantity":10}
                """.formatted(uniqueSymbol());

        mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsStringIgnoringCase("price")));

        assertEquals(0, orderRepository.count(), "a rejected order must not reach the database");
    }

    /** Zero is non-positive too, and would have made a resting order fillable for nothing. */
    @Test
    void submitOrder_zeroPrice_returns400() throws Exception {
        String token = registerAndLogin("zero-price-" + UUID.randomUUID());
        String body = """
                {"symbol":"%s","side":"SELL","type":"LIMIT","price":0,"quantity":10}
                """.formatted(uniqueSymbol());

        mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submitOrder_validPositivePrice_stillAccepted() throws Exception {
        String token = registerAndLogin("pos-price-" + UUID.randomUUID());

        UUID orderId = submitOrder(token, uniqueSymbol(), "BUY", "LIMIT", "100.25", "10");

        Order persisted = orderRepository.findById(orderId).orElseThrow();
        assertEquals(0, new BigDecimal("100.25").compareTo(persisted.getPrice()));
    }

    /**
     * The other half of the fix: {@code @Positive} treats null as valid, which is
     * exactly what a MARKET order needs -- it legitimately carries no price. Guards
     * against "fixing" the negative-price hole by making price mandatory.
     */
    @Test
    void submitOrder_marketOrderWithNoPrice_stillAccepted() throws Exception {
        String token = registerAndLogin("market-" + UUID.randomUUID());
        String body = """
                {"symbol":"%s","side":"BUY","type":"MARKET","quantity":10}
                """.formatted(uniqueSymbol());

        mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.type").value("MARKET"));
    }

    @Test
    void endToEndFlow_registerLoginPlaceOrderGetOrderCancelOrder() throws Exception {
        String username = "e2e-" + UUID.randomUUID();
        String token = registerAndLogin(username);
        String symbol = uniqueSymbol();

        String createBody = objectMapper.writeValueAsString(
                new CreateOrderRequest(symbol, OrderSide.BUY, OrderType.LIMIT, new BigDecimal("50"), new BigDecimal("5")));

        MvcResult createResult = mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode created = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("order");
        UUID orderId = UUID.fromString(created.get("id").asText());
        assertEquals("OPEN", created.get("status").asText());

        mockMvc.perform(get("/api/orders/" + orderId).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId.toString()))
                .andExpect(jsonPath("$.symbol").value(symbol));

        mockMvc.perform(delete("/api/orders/" + orderId).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }
}
