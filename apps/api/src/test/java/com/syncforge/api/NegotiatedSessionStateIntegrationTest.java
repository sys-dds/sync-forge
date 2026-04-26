package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncforge.api.capability.ClientCapability;
import com.syncforge.api.protocol.ProtocolSession;
import com.syncforge.api.protocol.ProtocolSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class NegotiatedSessionStateIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    ProtocolSessionRepository protocolSessionRepository;

    @Test
    void acceptedNegotiationCreatesActiveSessionLookupByConnection() throws Exception {
        Fixture fixture = fixture();
        TestSocket socket = TestSocket.connect(websocketUri(), fixture.ownerId(), "device-a", "session-a", objectMapper);

        socket.send(Map.of(
                "type", "JOIN_ROOM",
                "messageId", "join",
                "roomId", fixture.roomId().toString(),
                "payload", Map.of(
                        "protocolVersion", 2,
                        "clientId", "client-a",
                        "capabilities", List.of("OPERATIONS", "RESUME", "BACKFILL", "SNAPSHOT"))));

        String connectionId = socket.nextOfType("JOINED_ROOM").get("connectionId").toString();
        ProtocolSession session = protocolSessionRepository.findActiveByConnectionId(connectionId).orElseThrow();

        assertThat(session.connectionId()).isEqualTo(connectionId);
        assertThat(session.websocketSessionId()).isNotBlank();
        assertThat(session.roomId()).isEqualTo(fixture.roomId());
        assertThat(session.userId()).isEqualTo(fixture.ownerId());
        assertThat(session.clientId()).isEqualTo("client-a");
        assertThat(session.deviceId()).isEqualTo("device-a");
        assertThat(session.clientSessionId()).isEqualTo("session-a");
        assertThat(session.requestedProtocolVersion()).isEqualTo(2);
        assertThat(session.negotiatedProtocolVersion()).isEqualTo(2);
        assertThat(session.serverPreferredProtocolVersion()).isEqualTo(2);
        assertThat(session.legacyDefaultApplied()).isFalse();
        assertThat(session.enabledCapabilities())
                .containsExactly(ClientCapability.OPERATIONS, ClientCapability.RESUME, ClientCapability.BACKFILL, ClientCapability.SNAPSHOT);
        assertThat(session.status()).isEqualTo("NEGOTIATED");

        socket.close();
    }

    @Test
    void rejectedNegotiationIsNotTreatedAsActive() throws Exception {
        Fixture fixture = fixture();
        TestSocket socket = TestSocket.connect(websocketUri(), fixture.ownerId(), "bad-device", "bad-session", objectMapper);

        socket.send(Map.of(
                "type", "JOIN_ROOM",
                "messageId", "bad",
                "roomId", fixture.roomId().toString(),
                "payload", Map.of("protocolVersion", 0)));

        socket.nextOfType("PROTOCOL_REJECTED");
        Integer protocolRows = jdbcTemplate.queryForObject(
                "select count(*) from websocket_protocol_sessions where room_id = ?",
                Integer.class,
                fixture.roomId());
        assertThat(protocolRows).isZero();

        socket.close();
    }

    @Test
    void leaveClosesNegotiatedSession() throws Exception {
        Fixture fixture = fixture();
        TestSocket socket = TestSocket.connect(websocketUri(), fixture.ownerId(), "device-b", "session-b", objectMapper);

        socket.send(Map.of("type", "JOIN_ROOM", "messageId", "join", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        String connectionId = socket.nextOfType("JOINED_ROOM").get("connectionId").toString();
        assertThat(protocolSessionRepository.findActiveByConnectionId(connectionId)).isPresent();

        socket.send(Map.of("type", "LEAVE_ROOM", "messageId", "leave", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        socket.nextOfType("LEFT_ROOM");

        assertThat(protocolSessionRepository.findActiveByConnectionId(connectionId)).isEmpty();
        ProtocolSession closed = protocolSessionRepository.findByConnectionId(connectionId).orElseThrow();
        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.closedAt()).isNotNull();

        socket.close();
    }
}
