package com.syncforge.api.backpressure.application;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncforge.api.node.NodeIdentity;
import com.syncforge.api.websocket.JoinedRoomConnection;
import com.syncforge.api.websocket.RoomWebSocketEnvelope;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@Component
public class BoundedWebSocketSender {
    private final ObjectMapper objectMapper;
    private final ConnectionFlowControlService flowControlService;
    private final SlowConsumerService slowConsumerService;
    private final NodeIdentity nodeIdentity;
    private final Map<String, ArrayBlockingQueue<String>> queues = new ConcurrentHashMap<>();

    public BoundedWebSocketSender(
            ObjectMapper objectMapper,
            ConnectionFlowControlService flowControlService,
            SlowConsumerService slowConsumerService,
            NodeIdentity nodeIdentity) {
        this.objectMapper = objectMapper;
        this.flowControlService = flowControlService;
        this.slowConsumerService = slowConsumerService;
        this.nodeIdentity = nodeIdentity;
    }

    public void register(JoinedRoomConnection connection) {
        queues.put(connection.connectionId(), new ArrayBlockingQueue<>(flowControlService.maxOutboundQueueSize()));
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
        ArrayBlockingQueue<String> queue = queues.computeIfAbsent(connection.connectionId(),
                ignored -> new ArrayBlockingQueue<>(flowControlService.maxOutboundQueueSize()));
        try {
            String payload = objectMapper.writeValueAsString(envelope);
            if (!queue.offer(payload)) {
                flowControlService.updateQueued(connection.connectionId(), queue.size());
                flowControlService.recordSendFailure(connection.connectionId(), "Outbound queue is full");
                return false;
            }
            int queuedMessages = queue.size();
            flowControlService.updateQueued(connection.connectionId(), queuedMessages);
            if (queuedMessages >= flowControlService.slowConsumerQueuedMessages()) {
                slowConsumerService.warn(connection.roomId(), connection.userId(), connection.connectionId(),
                        nodeIdentity.nodeId(), queuedMessages, flowControlService.slowConsumerQueuedMessages());
                queue.offer(objectMapper.writeValueAsString(new RoomWebSocketEnvelope(
                        "SLOW_CONSUMER_WARNING",
                        null,
                        connection.roomId().toString(),
                        connection.connectionId(),
                        Map.of(
                                "connectionId", connection.connectionId(),
                                "queuedMessages", queuedMessages,
                                "maxQueuedMessages", flowControlService.maxOutboundQueueSize()))));
            }
            drain(connection, queue);
            return true;
        } catch (Exception exception) {
            flowControlService.recordSendFailure(connection.connectionId(), exception.getMessage());
            return false;
        }
    }

    private void drain(JoinedRoomConnection connection, ArrayBlockingQueue<String> queue) throws IOException {
        WebSocketSession session = connection.webSocketSession();
        if (session == null || !session.isOpen()) {
            flowControlService.markClosed(connection.connectionId());
            return;
        }
        String payload;
        while ((payload = queue.poll()) != null) {
            flowControlService.updateQueued(connection.connectionId(), queue.size());
            flowControlService.markSendStarted(connection.connectionId());
            synchronized (session) {
                session.sendMessage(new TextMessage(payload));
            }
            flowControlService.markSendCompleted(connection.connectionId());
        }
        flowControlService.updateQueued(connection.connectionId(), 0);
    }
}
