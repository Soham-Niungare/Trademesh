package com.trademesh.backend.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over WebSocket, with a SockJS fallback -- chosen over plain/raw
 * WebSocket. STOMP's topic-based pub/sub ({@code SimpMessagingTemplate.convertAndSend})
 * is exactly "broadcast this payload to every subscriber of this destination,"
 * which is all this phase needs; a raw {@code WebSocketHandler} would mean
 * hand-rolling a session registry, subscribe/unsubscribe semantics, and
 * per-topic broadcast filtering that STOMP already provides out of the box.
 * SockJS adds broader browser/proxy compatibility for Phase 7's dashboard
 * client with no added server-side complexity.
 *
 * The endpoint is public/unauthenticated -- same reasoning as
 * {@code GET /api/market/**} (Phase 5): see {@code SecurityConfig} and
 * docs/websocket.md. One exception: {@link OrderUpdatedMessage} omits
 * {@code userId}, since this topic (unlike {@code /api/orders/**}) has no
 * per-caller access control to hide behind.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*").withSockJS();
    }
}
