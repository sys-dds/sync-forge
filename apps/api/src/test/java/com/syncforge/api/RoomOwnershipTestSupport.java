package com.syncforge.api;

import java.time.OffsetDateTime;
import java.util.Map;

import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.delivery.RoomEventOutboxDispatcher;
import com.syncforge.api.delivery.RoomEventOutboxRepository;
import com.syncforge.api.delivery.RoomEventOutboxStatus;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import com.syncforge.api.operation.store.OperationRepository;
import com.syncforge.api.ownership.RoomOwnershipLease;
import com.syncforge.api.ownership.RoomOwnershipRepository;
import com.syncforge.api.ownership.RoomOwnershipService;
import com.syncforge.api.operation.application.OperationCompactionService;
import com.syncforge.api.resume.application.ResumeWindowService;
import com.syncforge.api.snapshot.application.SnapshotReplayService;
import com.syncforge.api.snapshot.application.SnapshotService;
import org.springframework.beans.factory.annotation.Autowired;

abstract class RoomOwnershipTestSupport extends TextConvergenceTestSupport {
    static final String NODE_A = "node-a";
    static final String NODE_B = "node-b";

    @Autowired
    RoomOwnershipService ownershipService;

    @Autowired
    RoomOwnershipRepository ownershipRepository;

    @Autowired
    OperationRepository operationRepository;

    @Autowired
    RoomEventOutboxRepository outboxRepository;

    @Autowired
    RoomEventOutboxDispatcher outboxDispatcher;

    @Autowired
    DocumentStateService documentStateService;

    @Autowired
    ResumeWindowService resumeWindowService;

    @Autowired
    SnapshotService snapshotService;

    @Autowired
    SnapshotReplayService snapshotReplayService;

    @Autowired
    OperationCompactionService compactionService;

    RoomOwnershipLease acquire(Fixture fixture, String nodeId) {
        return ownershipService.acquireOrRenew(fixture.roomId(), nodeId);
    }

    RoomOwnershipLease takeover(Fixture fixture, String nodeId, long secondsAfterExpiry) {
        RoomOwnershipLease current = ownershipService.currentOwnership(fixture.roomId());
        OffsetDateTime takeoverAt = current.leaseExpiresAt().plusSeconds(secondsAfterExpiry);
        return ownershipService.takeoverExpired(fixture.roomId(), nodeId, "TEST_TAKEOVER", takeoverAt);
    }

    OperationSubmitResult submitAs(
            Fixture fixture,
            String nodeId,
            long fencingToken,
            String operationId,
            long clientSeq,
            long baseRevision,
            Map<String, Object> operation) {
        return operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                fixture.editorId(),
                "connection-" + operationId,
                "session-" + operationId,
                operationId,
                clientSeq,
                baseRevision,
                "TEXT_INSERT_AFTER",
                operation,
                false,
                null,
                null,
                null,
                java.util.List.of(),
                null,
                nodeId,
                fencingToken));
    }

    long outboxCountByStatus(RoomEventOutboxStatus status) {
        return outboxRepository.countByStatus(status);
    }
}
