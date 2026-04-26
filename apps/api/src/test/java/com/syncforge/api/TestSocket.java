package com.syncforge.api;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

class TestSocket {
    private final WebSocketSession session;
    private final ObjectMapper objectMapper;
    private final LinkedBlockingQueue<String> messages;

    private TestSocket(WebSocketSession session, ObjectMapper objectMapper, LinkedBlockingQueue<String> messages) {
        this.session = session;
        this.objectMapper = objectMapper;
        this.messages = messages;
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
        LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        TextWebSocketHandler handler = new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                messages.add(message.getPayload());
            }
        };
        WebSocketSession session = new StandardWebSocketClient().execute(handler, headers, uri).get(5, TimeUnit.SECONDS);
        return new TestSocket(session, objectMapper, messages);
    }

    void send(Map<String, Object> message) throws Exception {
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
    }

    void sendRaw(String message) throws Exception {
        session.sendMessage(new TextMessage(message));
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> next() throws Exception {
        String message = messages.poll(5, TimeUnit.SECONDS);
        if (message == null) {
            throw new AssertionError("Timed out waiting for WebSocket message");
        }
        return objectMapper.readValue(message, Map.class);
    }

    Map<String, Object> nextOfType(String type) throws Exception {
        return nextMatching(message -> type.equals(message.get("type")), "type " + type);
    }

    Map<String, Object> nextMatching(Predicate<Map<String, Object>> predicate, String description) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Map<String, Object> message = next();
            if (predicate.test(message)) {
                return message;
            }
        }
        throw new AssertionError("Timed out waiting for WebSocket message matching " + description);
    }

    boolean hasMessageWithin(long timeoutMillis) throws Exception {
        String message = messages.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        if (message != null) {
            messages.add(message);
            return true;
        }
        return false;
    }

    boolean hasMessageOfTypeWithin(String type, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            String message = messages.poll(Math.max(1, remainingMillis), TimeUnit.MILLISECONDS);
            if (message == null) {
                return false;
            }
            Map<String, Object> parsed = objectMapper.readValue(message, Map.class);
            if (type.equals(parsed.get("type"))) {
                return true;
            }
        }
        return false;
    }

    void drain() {
        messages.clear();
    }

    void close() throws Exception {
        session.close();
        Thread.sleep(100);
    }
}
