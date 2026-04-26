package com.syncforge.api.backpressure.application;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncforge.api.node.NodeIdentity;
import com.syncforge.api.websocket.JoinedRoomConnection;
import com.syncforge.api.websocket.RoomWebSocketEnvelope;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@Component
public class BoundedWebSocketSender {
    private final ObjectMapper objectMapper;
    private final ConnectionFlowControlService flowControlService;
    private final SlowConsumerService slowConsumerService;
    private final NodeIdentity nodeIdentity;
    private final ExecutorService drainExecutor;
    private final Map<String, QueueState> queues = new ConcurrentHashMap<>();

    public BoundedWebSocketSender(
            ObjectMapper objectMapper,
            ConnectionFlowControlService flowControlService,
            SlowConsumerService slowConsumerService,
            NodeIdentity nodeIdentity) {
        this.objectMapper = objectMapper;
        this.flowControlService = flowControlService;
        this.slowConsumerService = slowConsumerService;
        this.nodeIdentity = nodeIdentity;
        this.drainExecutor = Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors()),
                new SenderThreadFactory());
    }

    public void register(JoinedRoomConnection connection) {
        queues.put(connection.connectionId(), new QueueState(flowControlService.maxOutboundQueueSize()));
        flowControlService.register(connection);
    }

    public void unregister(JoinedRoomConnection connection) {
        if (connection == null) {
            return;
        }
        queues.remove(connection.connectionId());
        flowControlService.markClosed(connection.connectionId());
    }

    public boolean send(JoinedRoomConnection connection, RoomWebSocketEnvelope envelope) {
        if (flowControlService.isQuarantined(connection.connectionId())) {
            return true;
        }
        QueueState state = queues.computeIfAbsent(connection.connectionId(),
                ignored -> new QueueState(flowControlService.maxOutboundQueueSize()));
        try {
            String payload = objectMapper.writeValueAsString(envelope);
            if (!state.queue.offer(payload)) {
                flowControlService.updateQueued(connection.connectionId(), state.queue.size());
                flowControlService.recordSendFailure(connection.connectionId(), "Outbound queue is full");
                return false;
            }
            int queuedMessages = state.queue.size();
            flowControlService.updateQueued(connection.connectionId(), queuedMessages);
            if (queuedMessages >= flowControlService.slowConsumerQueuedMessages()) {
                enqueueSlowConsumerWarning(connection, state, queuedMessages);
            }
            scheduleDrain(connection, state);
            return true;
        } catch (Exception exception) {
            flowControlService.recordSendFailure(connection.connectionId(), exception.getMessage());
            return false;
        }
    }

    private void enqueueSlowConsumerWarning(JoinedRoomConnection connection, QueueState state, int queuedMessages) throws Exception {
        if (!state.warningQueued.compareAndSet(false, true)) {
            return;
        }
        slowConsumerService.warn(connection.roomId(), connection.userId(), connection.connectionId(),
                nodeIdentity.nodeId(), queuedMessages, flowControlService.slowConsumerQueuedMessages());
        String warningPayload = objectMapper.writeValueAsString(new RoomWebSocketEnvelope(
                "SLOW_CONSUMER_WARNING",
                null,
                connection.roomId().toString(),
                connection.connectionId(),
                Map.of(
                        "connectionId", connection.connectionId(),
                        "queuedMessages", queuedMessages,
                        "maxQueuedMessages", flowControlService.maxOutboundQueueSize())));
        if (state.queue.offer(warningPayload)) {
            flowControlService.updateQueued(connection.connectionId(), state.queue.size());
        }
    }

    private void scheduleDrain(JoinedRoomConnection connection, QueueState state) {
        if (!state.draining.compareAndSet(false, true)) {
            return;
        }
        drainExecutor.execute(() -> drain(connection, state));
    }

    private void drain(JoinedRoomConnection connection, QueueState state) {
        WebSocketSession session = connection.webSocketSession();
        if (session == null || !session.isOpen()) {
            flowControlService.markClosed(connection.connectionId());
            state.queue.clear();
            flowControlService.updateQueued(connection.connectionId(), 0);
            state.draining.set(false);
            return;
        }
        try {
            String payload;
            while ((payload = state.queue.poll()) != null) {
                flowControlService.updateQueued(connection.connectionId(), state.queue.size());
                flowControlService.markSendStarted(connection.connectionId());
                synchronized (session) {
                    session.sendMessage(new TextMessage(payload));
                }
                flowControlService.markSendCompleted(connection.connectionId());
            }
            state.warningQueued.set(false);
            flowControlService.updateQueued(connection.connectionId(), 0);
        } catch (IOException | RuntimeException exception) {
            state.queue.clear();
            flowControlService.recordSendFailure(connection.connectionId(), exception.getMessage());
            flowControlService.updateQueued(connection.connectionId(), 0);
        } finally {
            state.draining.set(false);
            if (!state.queue.isEmpty()) {
                scheduleDrain(connection, state);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        drainExecutor.shutdownNow();
    }

    private static final class QueueState {
        private final ArrayBlockingQueue<String> queue;
        private final AtomicBoolean draining = new AtomicBoolean(false);
        private final AtomicBoolean warningQueued = new AtomicBoolean(false);

        private QueueState(int capacity) {
            this.queue = new ArrayBlockingQueue<>(capacity);
        }
    }

    private static final class SenderThreadFactory implements ThreadFactory {
        private int count;

        @Override
        public synchronized Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "syncforge-websocket-sender-" + ++count);
            thread.setDaemon(true);
            return thread;
        }
    }
}
