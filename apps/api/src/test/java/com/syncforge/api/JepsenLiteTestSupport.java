package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.syncforge.api.delivery.RoomEventOutboxStatus;
import com.syncforge.api.runtime.RecommendedRuntimeAction;
import org.springframework.http.ResponseEntity;

abstract class JepsenLiteTestSupport extends RuntimeControlTestSupport {
    final JepsenLiteTimeline timeline = new JepsenLiteTimeline();

    void step(String action, String expected, Object actual) {
        timeline.record(action, expected, String.valueOf(actual));
    }

    void assertInvariantPass(Fixture fixture) {
        var snapshot = consistencyVerifier.verify(fixture.roomId());
        assertThat(snapshot.status().name())
                .withFailMessage(() -> "Invariant failure\n" + timeline.dump() + "\n" + snapshot.violations())
                .isEqualTo("PASS");
    }

    void assertInvariantFail(Fixture fixture, String code) {
        var snapshot = consistencyVerifier.verify(fixture.roomId());
        assertThat(snapshot.violations())
                .withFailMessage(() -> "Expected violation " + code + "\n" + timeline.dump())
                .anyMatch(violation -> code.equals(violation.code()));
    }

    void assertRoomSeqSafe(Fixture fixture) {
        assertThat(operationRepository.countDistinctRoomSeq(fixture.roomId())).isEqualTo(operationCount(fixture.roomId()));
        assertThat(operationRepository.countDistinctOperationIds(fixture.roomId())).isEqualTo(operationCount(fixture.roomId()));
    }

    void markFirstOutboxRetry(Fixture fixture, String error) {
        var record = outboxRepository.findByRoomSeq(fixture.roomId(), 1).orElseThrow();
        outboxRepository.markRetry(record.id(), error, OffsetDateTime.now().minusSeconds(1));
    }

    void deleteOutboxForRoomSeq(Fixture fixture, long roomSeq) {
        jdbcTemplate.update("delete from room_event_outbox where room_id = ? and room_seq = ?", fixture.roomId(), roomSeq);
    }

    void corruptChecksumOnly(Fixture fixture) {
        jdbcTemplate.update("""
                update document_live_states
                set content_checksum = 'bad-checksum',
                    updated_at = now()
                where room_id = ?
                """, fixture.roomId());
    }

    void corruptVisibleTextOnly(Fixture fixture, String content) {
        jdbcTemplate.update("""
                update document_live_states
                set content_text = ?,
                    updated_at = now()
                where room_id = ?
                """, content, fixture.roomId());
    }

    void corruptSnapshotChecksum(Fixture fixture) {
        jdbcTemplate.update("""
                update document_snapshots
                set content_checksum = 'bad-snapshot-checksum'
                where room_id = ?
                """, fixture.roomId());
    }

    void assertForbiddenGet(String path) {
        ResponseEntity<String> response = restTemplate.getForEntity(baseUrl + path, String.class);
        assertThat(response.getStatusCode().value())
                .withFailMessage(() -> "Expected forbidden for " + path + "\n" + timeline.dump())
                .isEqualTo(403);
    }

    void assertForbiddenPost(String path) {
        ResponseEntity<String> response = restTemplate.postForEntity(baseUrl + path, Map.of("reason", "forbidden"), String.class);
        assertThat(response.getStatusCode().value())
                .withFailMessage(() -> "Expected forbidden for " + path + "\n" + timeline.dump())
                .isEqualTo(403);
    }

    TestFailureHarness failureHarness(Fixture fixture) {
        return new TestFailureHarness(fixture);
    }

    final class TestFailureHarness {
        private final Fixture fixture;
        private boolean failSnapshotReplay;
        private boolean failOutboxPublishOnce;
        private String failReplayOperationId;

        TestFailureHarness(Fixture fixture) {
            this.fixture = fixture;
        }

        void failNextOutboxPublish() {
            failOutboxPublishOnce = true;
        }

        void drainWithInjectedPublishFailure() {
            if (failOutboxPublishOnce) {
                failOutboxPublishOnce = false;
                markFirstOutboxRetry(fixture, "INJECTED_OUTBOX_FAILURE");
            }
            deliveryRuntimeService.drain(fixture.roomId());
        }

        void failReplayOn(String operationId) {
            failReplayOperationId = operationId;
        }

        void replayAndQuarantineIfInjected(UUID actorUserId) {
            if (failReplayOperationId == null) {
                snapshotReplayService.replayFromLatestSnapshot(fixture.roomId());
                return;
            }
            var operation = operationRepository.findByRoomAndOperationId(fixture.roomId(), failReplayOperationId).orElseThrow();
            poisonOperationService.quarantine(fixture.roomId(), operation.operationId(), operation.roomSeq(),
                    "INJECTED_REPLAY_FAILURE", actorUserId);
        }

        void failSnapshotReplayValidation() {
            failSnapshotReplay = true;
            corruptSnapshotChecksum(fixture);
        }

        void repairExpectingFailure(UUID actorUserId) {
            assertThat(failSnapshotReplay).isTrue();
            try {
                repairService.rebuildState(fixture.roomId(), actorUserId, "injected failed repair");
            } catch (RuntimeException expected) {
                return;
            }
            throw new AssertionError("Expected injected snapshot replay failure");
        }

        void emitDuplicateStreamEventWithoutStateChange() {
            var record = outboxRepository.findByRoomSeq(fixture.roomId(), 1).orElseThrow();
            outboxRepository.markPublished(record.id(), "duplicate-stream-key", "0-duplicate");
        }
    }

    static final class JepsenLiteTimeline {
        private final List<String> steps = new ArrayList<>();

        void record(String action, String expected, String actual) {
            steps.add((steps.size() + 1) + ". action=" + action + " expected=" + expected + " actual=" + actual);
        }

        String dump() {
            return String.join(System.lineSeparator(), steps);
        }
    }

    void assertOverviewAction(Fixture fixture, RecommendedRuntimeAction expected) {
        assertThat(runtimeOverviewService.overview(fixture.roomId()).recommendedAction()).isEqualTo(expected);
    }
}
