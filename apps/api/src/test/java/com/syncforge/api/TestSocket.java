package com.syncforge.api;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

class TestSocket {
    private final WebSocketSession session;
    private final ObjectMapper objectMapper;
    private CompletableFuture<String> nextMessage;

    private TestSocket(WebSocketSession session, ObjectMapper objectMapper, CompletableFuture<String> nextMessage) {
        this.session = session;
        this.objectMapper = objectMapper;
        this.nextMessage = nextMessage;
    }

    static TestSocket connect(URI uri, UUID userId, String deviceId, String clientSessionId, ObjectMapper objectMapper) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add("X-User-Id", userId.toString());
        if (deviceId != null) {
            headers.add("X-Device-Id", deviceId);
        }
        if (clientSessionId != null) {
            headers.add("X-Session-Id", clientSessionId);
        }
        return connect(uri, headers, objectMapper);
    }

    static TestSocket connect(URI uri, WebSocketHttpHeaders headers, ObjectMapper objectMapper) throws Exception {
        CompletableFuture<String> nextMessage = new CompletableFuture<>();
        TestSocket[] holder = new TestSocket[1];
        TextWebSocketHandler handler = new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                holder[0].nextMessage.complete(message.getPayload());
            }
        };
        WebSocketSession session = new StandardWebSocketClient().execute(handler, headers, uri).get(5, TimeUnit.SECONDS);
        TestSocket socket = new TestSocket(session, objectMapper, nextMessage);
        holder[0] = socket;
        return socket;
    }

    void send(Map<String, Object> message) throws Exception {
        nextMessage = new CompletableFuture<>();
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
    }

    void sendRaw(String message) throws Exception {
        nextMessage = new CompletableFuture<>();
        session.sendMessage(new TextMessage(message));
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> next() throws Exception {
        return objectMapper.readValue(nextMessage.get(5, TimeUnit.SECONDS), Map.class);
    }

    void close() throws Exception {
        session.close();
    }
}
