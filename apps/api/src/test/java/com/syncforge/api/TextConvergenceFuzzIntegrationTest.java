package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.operation.application.CanonicalOperationPayloadHasher;
import com.syncforge.api.operation.application.OperationService;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import com.syncforge.api.operation.store.OperationRepository;
import com.syncforge.api.snapshot.application.SnapshotReplayService;
import com.syncforge.api.snapshot.application.SnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TextConvergenceFuzzIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    OperationService operationService;

    @Autowired
    OperationRepository operationRepository;

    @Autowired
    DocumentStateService documentStateService;

    @Autowired
    SnapshotService snapshotService;

    @Autowired
    SnapshotReplayService snapshotReplayService;

    @Autowired
    CanonicalOperationPayloadHasher payloadHasher;

    @Test
    void deterministicTextConvergenceFuzz() {
        for (long seed : List.of(76088L, 76089L)) {
            runSeed(seed);
        }
    }

    private void runSeed(long seed) {
        Fixture fixture = fixture();
        Random random = new Random(seed);
        List<String> atomIds = new ArrayList<>();
        List<String> visibleAtomIds = new ArrayList<>();
        List<String> timeline = new ArrayList<>();
        AcceptedOperation lastAccepted = null;
        long clientSeq = 1;
        try {
            for (int step = 0; step < 18; step++) {
                long baseRevision = documentStateService.getOrInitialize(fixture.roomId()).currentRevision();
                long baseRoomSeq = documentStateService.getOrInitialize(fixture.roomId()).currentRoomSeq();
                int choice = random.nextInt(6);
                if (choice <= 2 || visibleAtomIds.isEmpty()) {
                    String operationId = "fuzz-" + seed + "-insert-" + step;
                    String anchor = atomIds.isEmpty() || random.nextBoolean()
                            ? "START"
                            : atomIds.get(random.nextInt(atomIds.size()));
                    Map<String, Object> payload = Map.of("anchorAtomId", anchor, "text", Character.toString((char) ('a' + step)));
                    boolean offline = random.nextBoolean();
                    OperationSubmitResult result = submit(fixture, operationId, "client-" + operationId, clientSeq++,
                            baseRevision, baseRoomSeq, "TEXT_INSERT_AFTER", payload, offline);
                    timeline.add((offline ? "offline " : "online ") + operationId + " after " + anchor + " -> " + result.accepted());
                    assertThat(result.accepted()).isTrue();
                    String atomId = operationId + ":0";
                    atomIds.add(atomId);
                    visibleAtomIds.add(atomId);
                    lastAccepted = new AcceptedOperation(operationId, "client-" + operationId, "TEXT_INSERT_AFTER", payload);
                } else if (choice == 3) {
                    String target = visibleAtomIds.remove(random.nextInt(visibleAtomIds.size()));
                    String operationId = "fuzz-" + seed + "-delete-" + step;
                    Map<String, Object> payload = Map.of("atomIds", List.of(target));
                    boolean offline = random.nextBoolean();
                    OperationSubmitResult result = submit(fixture, operationId, "client-" + operationId, clientSeq++,
                            baseRevision, baseRoomSeq, "TEXT_DELETE_ATOMS", payload, offline);
                    timeline.add((offline ? "offline " : "online ") + operationId + " delete " + target + " -> " + result.accepted());
                    assertThat(result.accepted()).isTrue();
                    lastAccepted = new AcceptedOperation(operationId, "client-" + operationId, "TEXT_DELETE_ATOMS", payload);
                } else if (choice == 4 && lastAccepted != null) {
                    OperationSubmitResult duplicate = submit(fixture, lastAccepted.operationId(), lastAccepted.clientOperationId(),
                            clientSeq++, baseRevision, baseRoomSeq, lastAccepted.operationType(), lastAccepted.payload(), true);
                    timeline.add("duplicate " + lastAccepted.operationId() + " -> " + duplicate.duplicate());
                    assertThat(duplicate.accepted()).isTrue();
                    assertThat(duplicate.duplicate()).isTrue();
                } else {
                    String operationId = "fuzz-" + seed + "-invalid-" + step;
                    OperationSubmitResult rejected = submit(fixture, operationId, "client-" + operationId, clientSeq++,
                            Math.max(0, baseRevision - 1), baseRoomSeq, "TEXT_INSERT_AFTER",
                            Map.of("anchorAtomId", "missing-" + step + ":0", "text", "x"), true);
                    timeline.add("invalid " + operationId + " -> " + rejected.code());
                    assertThat(rejected.accepted()).isFalse();
                }

                if (step == 8) {
                    snapshotService.createSnapshot(fixture.roomId(), "MANUAL");
                    timeline.add("snapshot at step " + step);
                }
            }

            DocumentStateService.ReplayResult replay = documentStateService.replayOperations(
                    operationRepository.findByRoom(fixture.roomId()), "");
            assertThat(replay.content())
                    .withFailMessage(() -> failure(seed, timeline, "live/replay equivalence"))
                    .isEqualTo(documentStateService.getOrInitialize(fixture.roomId()).contentText());
            snapshotReplayService.replayFromLatestSnapshot(fixture.roomId());
            assertThat(documentStateService.verifyFullReplayEquivalence(fixture.roomId()))
                    .withFailMessage(() -> failure(seed, timeline, "snapshot/replay equivalence"))
                    .isTrue();
        } catch (AssertionError error) {
            throw new AssertionError(failure(seed, timeline, "text convergence fuzz"), error);
        }
    }

    private OperationSubmitResult submit(Fixture fixture, String operationId, String clientOperationId, long clientSeq,
            long baseRevision, long baseRoomSeq, String operationType, Map<String, Object> payload, boolean offline) {
        return operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                fixture.editorId(),
                "connection-" + operationId,
                "session-" + operationId,
                operationId,
                clientSeq,
                baseRevision,
                operationType,
                payload,
                offline,
                offline ? clientOperationId : null,
                offline ? baseRoomSeq : null,
                null,
                List.of(),
                offline ? payloadHasher.hash(operationType, payload) : null));
    }

    private String failure(long seed, List<String> timeline, String invariant) {
        return "seed=" + seed + "\ninvariant=" + invariant + "\ntimeline=\n" + String.join("\n", timeline);
    }

    private record AcceptedOperation(
            String operationId,
            String clientOperationId,
            String operationType,
            Map<String, Object> payload
    ) {
    }
}
