package com.trademesh.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.trademesh.backend.controller.CreateOrderRequest;
import com.trademesh.backend.controller.LoginRequest;
import com.trademesh.backend.controller.LoginResponse;
import com.trademesh.backend.controller.RegisterRequest;
import com.trademesh.backend.engine.OrderBookSnapshot;
import com.trademesh.backend.entity.Order;
import com.trademesh.backend.entity.OrderSide;
import com.trademesh.backend.entity.OrderStatus;
import com.trademesh.backend.entity.OrderType;
import com.trademesh.backend.entity.Trade;
import com.trademesh.backend.repository.OrderRepository;
import com.trademesh.backend.repository.TradeRepository;
import com.trademesh.backend.service.MarketDataService;
import com.trademesh.backend.websocket.TradeExecutedMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;
import org.testcontainers.containers.GenericContainer;
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
 * The one test that exercises every link of the write path in a single continuous
 * flow: REST -> matching engine -> Postgres -> Redis projection -> WebSocket
 * broadcast.
 *
 * <p>Every other test in the suite deliberately stubs out one link, which is
 * reasonable in isolation but leaves the seams between them unverified:
 * {@code OrderControllerTest} runs no Redis container, {@code MarketDataServiceTest}
 * calls {@code TradeService} directly and never goes over HTTP, and
 * {@code WebSocketPublisherTest} omits Redis on purpose. Nothing before this asserted
 * that one REST call produces a persisted trade <em>and</em> a refreshed Redis
 * snapshot <em>and</em> a broadcast message -- so a regression in how the two
 * {@code @EventListener}s coexist (one throwing and suppressing the other, an
 * ordering assumption, a transaction-boundary change) could not have been caught.
 *
 * <p>The scenario is a <em>partial</em> fill rather than a clean one, deliberately: a
 * full match empties the book, and an empty Redis snapshot is indistinguishable from
 * one that was never written. Leaving 6 resting means the Redis assertion has to
 * match a specific non-empty value to pass.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class FullOrderFlowIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TradeRepository tradeRepository;

    @Autowired
    private MarketDataService marketDataService;

    private static void assertMoneyEquals(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }

    @Test
    void restOrderMatch_persistsTrade_updatesBothOrders_refreshesRedis_andBroadcastsOverWebSocket() throws Exception {
        String symbol = "E2E-" + UUID.randomUUID();
        String sellerToken = registerAndLogin("seller-" + UUID.randomUUID());
        String buyerToken = registerAndLogin("buyer-" + UUID.randomUUID());

        // Subscribe before anything is placed -- these topics carry no history, so a
        // late subscription would miss the broadcast entirely and the assertion would
        // be testing the test's own timing rather than the application.
        StompSession session = connectStompClient();
        BlockingQueue<TradeExecutedMessage> broadcastTrades =
                subscribe(session, "/topic/market/" + symbol + "/trades", TradeExecutedMessage.class);

        UUID sellOrderId = submitOrder(sellerToken, symbol, OrderSide.SELL, "100", "10");
        UUID buyOrderId = submitOrder(buyerToken, symbol, OrderSide.BUY, "100", "4");

        // --- 1. The trade is durable in Postgres ---
        List<Trade> trades = tradeRepository.findBySymbolOrderByExecutedAtDesc(symbol);
        assertEquals(1, trades.size(), "exactly one trade should have been persisted");
        Trade trade = trades.get(0);
        assertMoneyEquals("100", trade.getPrice());
        assertMoneyEquals("4", trade.getQuantity());
        assertEquals(buyOrderId, trade.getBuyOrderId());
        assertEquals(sellOrderId, trade.getSellOrderId());
        assertNotNull(trade.getExecutedAt());

        // --- 2. Both orders' state is correct in Postgres ---
        Order sellOrder = orderRepository.findById(sellOrderId).orElseThrow();
        Order buyOrder = orderRepository.findById(buyOrderId).orElseThrow();

        assertEquals(OrderStatus.PARTIALLY_FILLED, sellOrder.getStatus());
        assertMoneyEquals("6", sellOrder.getRemainingQuantity());

        assertEquals(OrderStatus.FILLED, buyOrder.getStatus());
        assertMoneyEquals("0", buyOrder.getRemainingQuantity());

        // --- 3. Redis reflects the post-trade book ---
        // No polling: MarketDataService's listener runs synchronously on the calling
        // thread, after commit and before the POST response is written, so Redis is
        // already current by the time the request above returned.
        OrderBookSnapshot snapshot = marketDataService.getSnapshot(symbol);
        assertEquals(1, snapshot.asks().size(), "the unfilled remainder should still be resting");
        assertMoneyEquals("100", snapshot.asks().get(0).price());
        assertMoneyEquals("6", snapshot.asks().get(0).totalQuantity());
        assertTrue(snapshot.bids().isEmpty(), "the buy was fully filled and should not rest");

        // ...and the public read endpoint serves that same Redis state, which is what
        // a client actually sees.
        ResponseEntity<JsonNode> bookResponse =
                restTemplate.getForEntity("/api/market/" + symbol + "/orderbook", JsonNode.class);
        assertEquals(200, bookResponse.getStatusCode().value());
        JsonNode bestAsk = bookResponse.getBody().get("bestAsk");
        assertMoneyEquals("100", new BigDecimal(bestAsk.get("price").asText()));
        assertMoneyEquals("6", new BigDecimal(bestAsk.get("totalQuantity").asText()));
        assertTrue(bookResponse.getBody().get("bestBid").isNull(), "no bids should remain");

        // --- 4. The trade was broadcast over WebSocket ---
        TradeExecutedMessage broadcast = broadcastTrades.poll(5, TimeUnit.SECONDS);
        assertNotNull(broadcast, "expected a TRADE_EXECUTED message on the symbol's trades topic");
        assertEquals(symbol, broadcast.symbol());
        assertMoneyEquals("100", broadcast.price());
        assertMoneyEquals("4", broadcast.quantity());
        assertEquals(buyOrderId, broadcast.buyOrderId());
        assertEquals(sellOrderId, broadcast.sellOrderId());

        // The broadcast must describe the same trade that was persisted, not merely a
        // structurally-valid message -- this is the seam between the two listeners.
        assertEquals(trade.getBuyOrderId(), broadcast.buyOrderId());
        assertEquals(trade.getSellOrderId(), broadcast.sellOrderId());

        session.disconnect();
    }

    private String registerAndLogin(String username) {
        String password = "password123";
        restTemplate.postForEntity("/api/auth/register",
                new RegisterRequest(username, username + "@example.com", password), Void.class);
        return restTemplate.postForObject("/api/auth/login",
                new LoginRequest(username, password), LoginResponse.class).token();
    }

    private UUID submitOrder(String token, String symbol, OrderSide side, String price, String quantity) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        CreateOrderRequest body = new CreateOrderRequest(symbol, side, OrderType.LIMIT,
                new BigDecimal(price), new BigDecimal(quantity));

        ResponseEntity<JsonNode> response = restTemplate.exchange("/api/orders", HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
        assertEquals(201, response.getStatusCode().value(), () -> "order submission failed: " + response.getBody());
        return UUID.fromString(response.getBody().get("order").get("id").asText());
    }

    private StompSession connectStompClient() throws Exception {
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
        // Bounded settle time so the SUBSCRIBE frame reaches the broker before the
        // caller triggers the publish -- same approach as WebSocketPublisherTest.
        Thread.sleep(200);
        return messages;
    }
}
