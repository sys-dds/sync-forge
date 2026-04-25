package com.syncforge.api.websocket;

import java.util.Map;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Component
public class RoomWebSocketHandshakeInterceptor implements HandshakeInterceptor {
    static final String USER_ID_ATTRIBUTE = "syncforge.userId";
    static final String DEVICE_ID_ATTRIBUTE = "syncforge.deviceId";
    static final String SESSION_ID_ATTRIBUTE = "syncforge.sessionId";

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {
        attributes.put(USER_ID_ATTRIBUTE, firstHeader(request, "X-User-Id"));
        attributes.put(DEVICE_ID_ATTRIBUTE, firstHeader(request, "X-Device-Id"));
        attributes.put(SESSION_ID_ATTRIBUTE, firstHeader(request, "X-Session-Id"));
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {
    }

    private String firstHeader(ServerHttpRequest request, String name) {
        return request.getHeaders().getFirst(name);
    }
}
