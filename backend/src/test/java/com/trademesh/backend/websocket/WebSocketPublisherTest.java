package com.trademesh.backend.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.trademesh.backend.controller.CreateOrderRequest;
import com.trademesh.backend.controller.LoginRequest;
import com.trademesh.backend.controller.LoginResponse;
import com.trademesh.backend.controller.RegisterRequest;
import com.trademesh.backend.entity.OrderSide;
import com.trademesh.backend.entity.OrderStatus;
import com.trademesh.backend.entity.OrderType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A real, ephemeral Postgres (Testcontainers) plus a real embedded HTTP server
 * (RANDOM_PORT -- WebSocket needs an actual socket, unlike MockMvc's in-memory
 * dispatch) and a real STOMP-over-SockJS client, driving the whole path:
 * REST order placement -> TradeService -> TradeExecutedEvent/OrderCancelledEvent
 * -> WebSocketPublisher -> a subscribed client. No Redis is configured here
 * deliberately: MarketDataService's own listener silently no-ops if Redis is
 * unreachable (see docs/market-data.md), so it's irrelevant to what this class
 * tests and adding it would just be one more container for no reason.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class WebSocketPublisherTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private static String uniqueSymbol() {
        return "SYM-" + UUID.randomUUID();
    }

    private static void assertMoneyEquals(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }

    private String registerAndLogin(String username) {
        String password = "password123";
        restTemplate.postForEntity("/api/auth/register",
                new RegisterRequest(username, username + "@example.com", password), Void.class);
        LoginResponse loginResponse = restTemplate.postForObject("/api/auth/login",
                new LoginRequest(username, password), LoginResponse.class);
        return loginResponse.token();
    }

    private UUID submitOrder(String token, String symbol, OrderSide side, String price, String quantity) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        CreateOrderRequest body = new CreateOrderRequest(symbol, side, OrderType.LIMIT,
                new BigDecimal(price), new BigDecimal(quantity));

        ResponseEntity<JsonNode> response = restTemplate.exchange("/api/orders", HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
        return UUID.fromString(response.getBody().get("order").get("id").asText());
    }

    private void cancelOrder(String token, UUID orderId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        restTemplate.exchange("/api/orders/" + orderId, HttpMethod.DELETE, new HttpEntity<>(headers), Void.class);
    }

    private StompSession connect() throws Exception {
        WebSocketStompClient stompClient = new WebSocketStompClient(
                new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient()))));
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
        return stompClient.connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {
        }).get(5, TimeUnit.SECONDS);
    }

    private <T> BlockingQueue<T> subscribe(StompSession session, String destination, Class<T> payloadType)
            throws InterruptedException {
        BlockingQueue<T> messages = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return payloadType;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                messages.add(payloadType.cast(payload));
            }
        });
        // Give the SUBSCRIBE frame time to reach the broker before the caller
        // triggers whatever's supposed to publish to it -- a small, bounded
        // settle time, not a polling loop.
        Thread.sleep(200);
        return messages;
    }

    @Test
    void placingMatchingOrders_publishesTradeExecuted() throws Exception {
        String token = registerAndLogin("ws-trader-" + UUID.randomUUID());
        String symbol = uniqueSymbol();

        StompSession session = connect();
        BlockingQueue<TradeExecutedMessage> trades =
                subscribe(session, "/topic/market/" + symbol + "/trades", TradeExecutedMessage.class);

        submitOrder(token, symbol, OrderSide.SELL, "100", "10");
        submitOrder(token, symbol, OrderSide.BUY, "100", "10");

        TradeExecutedMessage trade = trades.poll(5, TimeUnit.SECONDS);
        assertNotNull(trade, "expected a TRADE_EXECUTED message");
        assertEquals(symbol, trade.symbol());
        assertMoneyEquals("100", trade.price());
        assertMoneyEquals("10", trade.quantity());

        session.disconnect();
    }

    @Test
    void restingOrder_publishesOrderUpdatedAndOrderBookUpdated() throws Exception {
        String token = registerAndLogin("ws-trader-" + UUID.randomUUID());
        String symbol = uniqueSymbol();

        StompSession session = connect();
        BlockingQueue<OrderUpdatedMessage> orders =
                subscribe(session, "/topic/market/" + symbol + "/orders", OrderUpdatedMessage.class);
        BlockingQueue<OrderBookUpdatedMessage> books =
                subscribe(session, "/topic/market/" + symbol + "/orderbook", OrderBookUpdatedMessage.class);

        submitOrder(token, symbol, OrderSide.SELL, "100", "10");

        OrderUpdatedMessage orderUpdate = orders.poll(5, TimeUnit.SECONDS);
        assertNotNull(orderUpdate, "expected an ORDER_UPDATED message");
        assertEquals(symbol, orderUpdate.symbol());
        assertEquals(OrderStatus.OPEN, orderUpdate.status());
        assertMoneyEquals("10", orderUpdate.remainingQuantity());

        OrderBookUpdatedMessage book = books.poll(5, TimeUnit.SECONDS);
        assertNotNull(book, "expected an ORDER_BOOK_UPDATED message");
        assertEquals(symbol, book.symbol());
        assertEquals(1, book.asks().size());
        assertMoneyEquals("100", book.asks().get(0).price());
        assertMoneyEquals("10", book.asks().get(0).totalQuantity());
        assertTrue(book.bids().isEmpty());

        session.disconnect();
    }

    @Test
    void cancellingOrder_publishesOrderUpdatedWithCancelledStatus() throws Exception {
        String token = registerAndLogin("ws-trader-" + UUID.randomUUID());
        String symbol = uniqueSymbol();
        UUID orderId = submitOrder(token, symbol, OrderSide.SELL, "100", "10");

        StompSession session = connect();
        BlockingQueue<OrderUpdatedMessage> orders =
                subscribe(session, "/topic/market/" + symbol + "/orders", OrderUpdatedMessage.class);
        BlockingQueue<OrderBookUpdatedMessage> books =
                subscribe(session, "/topic/market/" + symbol + "/orderbook", OrderBookUpdatedMessage.class);

        cancelOrder(token, orderId);

        OrderUpdatedMessage orderUpdate = orders.poll(5, TimeUnit.SECONDS);
        assertNotNull(orderUpdate, "expected an ORDER_UPDATED message for the cancellation");
        assertEquals(orderId, orderUpdate.orderId());
        assertEquals(OrderStatus.CANCELLED, orderUpdate.status());

        OrderBookUpdatedMessage book = books.poll(5, TimeUnit.SECONDS);
        assertNotNull(book, "expected an ORDER_BOOK_UPDATED message for the cancellation");
        assertTrue(book.asks().isEmpty());

        session.disconnect();
    }
}
