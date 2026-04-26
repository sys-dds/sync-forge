package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.documentstate.model.DocumentLiveState;
import com.syncforge.api.harness.DeterministicCollaborationHarness;
import com.syncforge.api.operation.application.OperationService;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReplayDeterminismIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    OperationService operationService;

    @Autowired
    DocumentStateService documentStateService;

    @Test
    void operationLogReplayIsDeterministicAndPersistsRebuildRuns() {
        DeterministicCollaborationHarness.Fixture fixture = harness().createFixture(38001);

        OperationSubmitResult direct = submit(fixture, "owner", "direct-insert", 1, 0, "TEXT_INSERT",
                Map.of("position", 0, "text", "a"));
        OperationSubmitResult transformed = submit(fixture, "editor", "transformed-insert", 1, 0, "TEXT_INSERT",
                Map.of("position", 0, "text", "b"));
        OperationSubmitResult noop = submit(fixture, "owner", "noop-op", 2, transformed.revision(), "NOOP", Map.of());
        OperationSubmitResult duplicate = submit(fixture, "owner", "direct-insert", 99, 0, "TEXT_INSERT",
                Map.of("position", 0, "text", "a"));
        OperationSubmitResult rejected = submit(fixture, "owner", "rejected-unsafe", 100, noop.revision(), "TEXT_DELETE",
                Map.of("position", 999, "length", 1));

        assertThat(direct.accepted()).isTrue();
        assertThat(transformed.accepted()).isTrue();
        assertThat(transformed.transformed()).isTrue();
        assertThat(noop.accepted()).isTrue();
        assertThat(duplicate.accepted()).isTrue();
        assertThat(duplicate.duplicate()).isTrue();
        assertThat(rejected.accepted()).isFalse();

        long operationCountBeforeReplay = operationCount(fixture);
        DocumentLiveState live = documentStateService.getOrInitialize(fixture.roomId());

        DocumentStateService.RebuildResult first = documentStateService.rebuildFromOperationLog(fixture.roomId());
        DocumentStateService.RebuildResult second = documentStateService.rebuildFromOperationLog(fixture.roomId());

        assertThat(first.replayEquivalent()).isTrue();
        assertThat(second.replayEquivalent()).isTrue();
        assertThat(second.state().contentText()).isEqualTo(live.contentText());
        assertThat(second.state().contentChecksum()).isEqualTo(live.contentChecksum());
        assertThat(second.state().currentRevision()).isEqualTo(live.currentRevision());
        assertThat(second.state().currentRoomSeq()).isEqualTo(live.currentRoomSeq());
        assertThat(operationCount(fixture)).isEqualTo(operationCountBeforeReplay);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*)
                from document_state_rebuild_runs
                where room_id = ? and status = 'COMPLETED'
                """, Long.class, fixture.roomId())).isGreaterThanOrEqualTo(2L);

        jdbcTemplate.update("""
                update room_operations
                set operation_json = cast(? as jsonb)
                where room_id = ? and operation_id = ?
                """, "{\"position\":999,\"length\":1}", fixture.roomId(), "direct-insert");
        assertThatThrownBy(() -> documentStateService.rebuildFromOperationLog(fixture.roomId()))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*)
                from document_state_rebuild_runs
                where room_id = ? and status = 'FAILED'
                """, Long.class, fixture.roomId())).isGreaterThanOrEqualTo(1L);
    }

    private OperationSubmitResult submit(
            DeterministicCollaborationHarness.Fixture fixture,
            String clientKey,
            String operationId,
            long clientSeq,
            long baseRevision,
            String operationType,
            Map<String, Object> operation) {
        return operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                fixture.clients().get(clientKey).userId(),
                clientKey + "-connection",
                clientKey + "-session",
                operationId,
                clientSeq,
                baseRevision,
                operationType,
                operation));
    }

    private long operationCount(DeterministicCollaborationHarness.Fixture fixture) {
        return jdbcTemplate.queryForObject("select count(*) from room_operations where room_id = ?",
                Long.class, fixture.roomId());
    }

    private DeterministicCollaborationHarness harness() {
        return new DeterministicCollaborationHarness(restTemplate, baseUrl, jdbcTemplate, operationService, documentStateService);
    }
}
