package com.syncforge.api.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class RoomWebSocketConfig implements WebSocketConfigurer {
    private final RoomWebSocketHandler roomWebSocketHandler;
    private final RoomWebSocketHandshakeInterceptor handshakeInterceptor;

    public RoomWebSocketConfig(
            RoomWebSocketHandler roomWebSocketHandler,
            RoomWebSocketHandshakeInterceptor handshakeInterceptor) {
        this.roomWebSocketHandler = roomWebSocketHandler;
        this.handshakeInterceptor = handshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(roomWebSocketHandler, "/ws/rooms")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOrigins("*");
    }
}
