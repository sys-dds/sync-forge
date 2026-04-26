package com.syncforge.api.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.syncforge.api.delivery.RoomEventOutboxDispatcher;
import com.syncforge.api.delivery.RoomEventOutboxRecord;
import com.syncforge.api.delivery.RoomEventOutboxRepository;
import com.syncforge.api.delivery.RoomEventOutboxStatus;
import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.documentstate.model.DocumentLiveState;
import com.syncforge.api.operation.application.CanonicalOperationPayloadHasher;
import com.syncforge.api.operation.application.OperationService;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import com.syncforge.api.resume.application.ClientOffsetService;
import com.syncforge.api.resume.application.RoomBackfillService;
import com.syncforge.api.resume.model.BackfillResult;
import com.syncforge.api.stream.application.NodeRoomSubscriptionService;
import com.syncforge.api.stream.application.RoomEventStreamConsumer;
import org.springframework.jdbc.core.JdbcTemplate;

public class OfflineCollaborationHarness {
    private final UUID roomId;
    private final Map<String, OfflineClient> clients;
    private final OperationService operationService;
    private final CanonicalOperationPayloadHasher payloadHasher;
    private final DocumentStateService documentStateService;
    private final RoomEventOutboxDispatcher outboxDispatcher;
    private final RoomEventStreamConsumer streamConsumer;
    private final NodeRoomSubscriptionService subscriptionService;
    private final ClientOffsetService clientOffsetService;
    private final RoomBackfillService roomBackfillService;
    private final RoomEventOutboxRepository outboxRepository;
    private final JdbcTemplate jdbcTemplate;

    public OfflineCollaborationHarness(
            UUID roomId,
            Map<String, UUID> clientUsers,
            OperationService operationService,
            CanonicalOperationPayloadHasher payloadHasher,
            DocumentStateService documentStateService,
            RoomEventOutboxDispatcher outboxDispatcher,
            RoomEventStreamConsumer streamConsumer,
            NodeRoomSubscriptionService subscriptionService,
            ClientOffsetService clientOffsetService,
            RoomBackfillService roomBackfillService,
            RoomEventOutboxRepository outboxRepository,
            JdbcTemplate jdbcTemplate) {
        this.roomId = roomId;
        this.clients = clientUsers.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> new OfflineClient(entry.getKey(), entry.getValue()),
                        (left, right) -> left,
                        java.util.LinkedHashMap::new));
        this.operationService = operationService;
        this.payloadHasher = payloadHasher;
        this.documentStateService = documentStateService;
        this.outboxDispatcher = outboxDispatcher;
        this.streamConsumer = streamConsumer;
        this.subscriptionService = subscriptionService;
        this.clientOffsetService = clientOffsetService;
        this.roomBackfillService = roomBackfillService;
        this.outboxRepository = outboxRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    public void connect(String clientKey) {
        client(clientKey).connected = true;
        subscriptionService.joined(roomId);
    }

    public void disconnect(String clientKey) {
        client(clientKey).connected = false;
        subscriptionService.left(roomId);
    }

    public OperationSubmitResult submitOnline(
            String clientKey,
            String operationId,
            long clientSeq,
            long baseRevision,
            String operationType,
            Map<String, Object> operation) {
        OfflineClient client = client(clientKey);
        return operationService.submit(new SubmitOperationCommand(
                roomId,
                client.userId,
                client.connectionId(),
                client.sessionId(),
                operationId,
                clientSeq,
                baseRevision,
                operationType,
                operation));
    }

    public OperationSubmitResult submitOffline(
            String clientKey,
            String operationId,
            String clientOperationId,
            long clientSeq,
            long baseRoomSeq,
            long baseRevision,
            String operationType,
            Map<String, Object> operation,
            Long dependsOnRoomSeq,
            List<String> dependsOnOperationIds) {
        OfflineClient client = client(clientKey);
        String canonicalHash = payloadHasher.hash(operationType, operation);
        return operationService.submit(new SubmitOperationCommand(
                roomId,
                client.userId,
                client.connectionId(),
                client.sessionId(),
                operationId,
                clientSeq,
                baseRevision,
                operationType,
                operation,
                true,
                clientOperationId,
                baseRoomSeq,
                dependsOnRoomSeq,
                dependsOnOperationIds,
                canonicalHash));
    }

    public int dispatchOutbox(int limit) {
        return outboxDispatcher.dispatchOnce(limit);
    }

    public int pollRoomStream() {
        return streamConsumer.pollRoom(roomId);
    }

    public boolean ack(String clientKey, long roomSeq) {
        OfflineClient client = client(clientKey);
        return clientOffsetService.acknowledge(roomId, client.userId, client.sessionId(), roomSeq);
    }

    public long lastAcked(String clientKey) {
        OfflineClient client = client(clientKey);
        return clientOffsetService.lastSeenOrDefault(roomId, client.userId, client.sessionId(), 0);
    }

    public BackfillResult backfill(String clientKey, long lastSeenRoomSeq) {
        OfflineClient client = client(clientKey);
        return roomBackfillService.backfill(roomId, client.userId, client.sessionId(), lastSeenRoomSeq);
    }

    public DocumentLiveState liveState() {
        return documentStateService.getOrInitialize(roomId);
    }

    public void assertReplayEqualsLive() {
        assertThat(documentStateService.verifyFullReplayEquivalence(roomId))
                .as("offline harness replay/live equivalence for room %s", roomId)
                .isTrue();
    }

    public long outboxCount(RoomEventOutboxStatus status) {
        return outboxRepository.countByStatus(status);
    }

    public Optional<RoomEventOutboxRecord> outboxForRoomSeq(long roomSeq) {
        return outboxRepository.findByRoomSeq(roomId, roomSeq);
    }

    public long acceptedOperationCount() {
        Long count = jdbcTemplate.queryForObject("""
                select count(*)
                from room_operations
                where room_id = ?
                """, Long.class, roomId);
        return count == null ? 0L : count;
    }

    public void removeMember(String clientKey) {
        jdbcTemplate.update("""
                update room_memberships
                set status = 'REMOVED'
                where room_id = ? and user_id = ?
                """, roomId, client(clientKey).userId);
    }

    public void assertNoDuplicateFanout(List<Map<String, Object>> events) {
        Set<String> logicalKeys = new LinkedHashSet<>();
        for (Map<String, Object> event : events) {
            String key = Objects.toString(event.get("roomSeq")) + ":" + Objects.toString(event.get("operationId"));
            assertThat(logicalKeys.add(key))
                    .as("duplicate logical fanout event %s in room %s", key, roomId)
                    .isTrue();
        }
    }

    public UUID roomId() {
        return roomId;
    }

    public UUID userId(String clientKey) {
        return client(clientKey).userId;
    }

    private OfflineClient client(String clientKey) {
        OfflineClient client = clients.get(clientKey);
        if (client == null) {
            throw new IllegalArgumentException("Unknown offline harness client " + clientKey);
        }
        return client;
    }

    private static final class OfflineClient {
        private final String key;
        private final UUID userId;
        private boolean connected;

        private OfflineClient(String key, UUID userId) {
            this.key = key;
            this.userId = userId;
        }

        private String connectionId() {
            return key + "-connection";
        }

        private String sessionId() {
            return key + "-session";
        }
    }
}
