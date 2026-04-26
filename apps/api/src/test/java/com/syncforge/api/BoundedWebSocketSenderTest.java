package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncforge.api.backpressure.application.BoundedWebSocketSender;
import com.syncforge.api.backpressure.application.ConnectionFlowControlService;
import com.syncforge.api.backpressure.application.SlowConsumerService;
import com.syncforge.api.node.NodeIdentity;
import com.syncforge.api.websocket.JoinedRoomConnection;
import com.syncforge.api.websocket.RoomWebSocketEnvelope;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class BoundedWebSocketSenderTest {

    @Test
    void slowSessionSendDoesNotBlockCaller() throws Exception {
        SenderFixture fixture = senderFixture(4, 3);
        CountDownLatch sendStarted = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        JoinedRoomConnection connection = connection("slow-connection", blockingSession(sendStarted, releaseSend));
        fixture.sender.register(connection);

        Instant started = Instant.now();
        boolean accepted = fixture.sender.send(connection, envelope("OPERATION_APPLIED"));

        assertThat(accepted).isTrue();
        assertThat(Duration.between(started, Instant.now()).toMillis()).isLessThan(200);
        assertThat(sendStarted.await(1, TimeUnit.SECONDS)).isTrue();

        releaseSend.countDown();
        verify(fixture.flowControlService, timeout(1000)).markSendCompleted("slow-connection");
    }

    @Test
    void slowSessionDoesNotBlockFastSession() throws Exception {
        SenderFixture fixture = senderFixture(4, 3);
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        CountDownLatch fastDelivered = new CountDownLatch(1);
        JoinedRoomConnection slow = connection("slow-connection", blockingSession(slowStarted, releaseSlow));
        JoinedRoomConnection fast = connection("fast-connection", notifyingSession(fastDelivered));
        fixture.sender.register(slow);
        fixture.sender.register(fast);

        assertThat(fixture.sender.send(slow, envelope("OPERATION_APPLIED"))).isTrue();
        assertThat(slowStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(fixture.sender.send(fast, envelope("OPERATION_APPLIED"))).isTrue();

        assertThat(fastDelivered.await(1, TimeUnit.SECONDS)).isTrue();
        releaseSlow.countDown();
    }

    @Test
    void queueOverflowFailsDeterministically() throws Exception {
        SenderFixture fixture = senderFixture(1, 99);
        CountDownLatch sendStarted = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        JoinedRoomConnection connection = connection("overflow-connection", blockingSession(sendStarted, releaseSend));
        fixture.sender.register(connection);

        assertThat(fixture.sender.send(connection, envelope("ONE"))).isTrue();
        assertThat(sendStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(fixture.sender.send(connection, envelope("TWO"))).isTrue();

        assertThat(fixture.sender.send(connection, envelope("THREE"))).isFalse();
        verify(fixture.flowControlService).recordSendFailure("overflow-connection", "Outbound queue is full");

        releaseSend.countDown();
    }

    @Test
    void slowConsumerWarningIsBoundedWhileQueueRemainsSlow() throws Exception {
        SenderFixture fixture = senderFixture(4, 1);
        CountDownLatch sendStarted = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        JoinedRoomConnection connection = connection("warning-connection", blockingSession(sendStarted, releaseSend));
        fixture.sender.register(connection);

        assertThat(fixture.sender.send(connection, envelope("ONE"))).isTrue();
        assertThat(sendStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(fixture.sender.send(connection, envelope("TWO"))).isTrue();
        assertThat(fixture.sender.send(connection, envelope("THREE"))).isTrue();

        verify(fixture.slowConsumerService, times(1)).warn(
                connection.roomId(), connection.userId(), "warning-connection", "test-node", 1, 1);

        releaseSend.countDown();
    }

    @Test
    void healthyDrainReturnsObservableQueueCountToZero() {
        SenderFixture fixture = senderFixture(4, 3);
        CountDownLatch delivered = new CountDownLatch(1);
        JoinedRoomConnection connection = connection("healthy-connection", notifyingSession(delivered));
        fixture.sender.register(connection);

        assertThat(fixture.sender.send(connection, envelope("OPERATION_APPLIED"))).isTrue();

        verify(fixture.flowControlService, timeout(1000).atLeastOnce()).updateQueued("healthy-connection", 0);
        verify(fixture.flowControlService, timeout(1000)).markSendCompleted("healthy-connection");
    }

    @Test
    void sendFailureIsRecorded() throws Exception {
        SenderFixture fixture = senderFixture(4, 3);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        doThrow(new IOException("boom")).when(session).sendMessage(any(TextMessage.class));
        JoinedRoomConnection connection = connection("failed-connection", session);
        fixture.sender.register(connection);

        assertThat(fixture.sender.send(connection, envelope("OPERATION_APPLIED"))).isTrue();

        verify(fixture.flowControlService, timeout(1000)).recordSendFailure("failed-connection", "boom");
    }

    @Test
    void runtimeSendFailureIsRecordedWhenSessionClosesDuringDrain() throws Exception {
        SenderFixture fixture = senderFixture(4, 3);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        doThrow(new IllegalStateException("session closed")).when(session).sendMessage(any(TextMessage.class));
        JoinedRoomConnection connection = connection("runtime-failed-connection", session);
        fixture.sender.register(connection);

        assertThat(fixture.sender.send(connection, envelope("OPERATION_APPLIED"))).isTrue();

        verify(fixture.flowControlService, timeout(1000))
                .recordSendFailure("runtime-failed-connection", "session closed");
        verify(fixture.flowControlService, timeout(1000).atLeastOnce())
                .updateQueued("runtime-failed-connection", 0);
    }

    @Test
    void closedConnectionIsMarkedClosed() {
        SenderFixture fixture = senderFixture(4, 3);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(false);
        JoinedRoomConnection connection = connection("closed-connection", session);
        fixture.sender.register(connection);

        assertThat(fixture.sender.send(connection, envelope("OPERATION_APPLIED"))).isTrue();

        verify(fixture.flowControlService, timeout(1000)).markClosed("closed-connection");
    }

    private SenderFixture senderFixture(int maxQueueSize, int slowThreshold) {
        ConnectionFlowControlService flowControlService = mock(ConnectionFlowControlService.class);
        SlowConsumerService slowConsumerService = mock(SlowConsumerService.class);
        when(flowControlService.maxOutboundQueueSize()).thenReturn(maxQueueSize);
        when(flowControlService.slowConsumerQueuedMessages()).thenReturn(slowThreshold);
        when(flowControlService.isQuarantined(anyString())).thenReturn(false);
        BoundedWebSocketSender sender = new BoundedWebSocketSender(
                new ObjectMapper(),
                flowControlService,
                slowConsumerService,
                new NodeIdentity("test-node", 30));
        return new SenderFixture(sender, flowControlService, slowConsumerService);
    }

    private JoinedRoomConnection connection(String connectionId, WebSocketSession session) {
        return new JoinedRoomConnection(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                connectionId,
                connectionId + "-socket",
                "device",
                "client-session",
                session);
    }

    private WebSocketSession blockingSession(CountDownLatch sendStarted, CountDownLatch releaseSend) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        doAnswer(invocation -> {
            sendStarted.countDown();
            assertThat(releaseSend.await(2, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
        return session;
    }

    private WebSocketSession notifyingSession(CountDownLatch delivered) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        try {
            doAnswer(invocation -> {
                delivered.countDown();
                return null;
            }).when(session).sendMessage(any(TextMessage.class));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        return session;
    }

    private RoomWebSocketEnvelope envelope(String type) {
        return new RoomWebSocketEnvelope(type, "message-id", "00000000-0000-0000-0000-000000000001", "connection-id",
                Map.of("value", type));
    }

    private record SenderFixture(
            BoundedWebSocketSender sender,
            ConnectionFlowControlService flowControlService,
            SlowConsumerService slowConsumerService) {
    }
}
