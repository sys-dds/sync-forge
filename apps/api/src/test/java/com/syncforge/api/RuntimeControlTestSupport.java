package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.runtime.OwnershipFencingAuditService;
import com.syncforge.api.runtime.PoisonOperationService;
import com.syncforge.api.runtime.RoomConsistencyVerifier;
import com.syncforge.api.runtime.RoomDeliveryRuntimeService;
import com.syncforge.api.runtime.RoomRepairService;
import com.syncforge.api.runtime.RoomRuntimeControlService;
import com.syncforge.api.runtime.RoomRuntimeHealthService;
import com.syncforge.api.runtime.RoomRuntimeOverviewService;
import com.syncforge.api.snapshot.model.DocumentSnapshot;
import com.syncforge.api.text.application.TextConvergenceService;
import org.springframework.beans.factory.annotation.Autowired;

abstract class RuntimeControlTestSupport extends RoomOwnershipTestSupport {
    @Autowired
    RoomRuntimeControlService runtimeControlService;

    @Autowired
    RoomRuntimeHealthService runtimeHealthService;

    @Autowired
    RoomConsistencyVerifier consistencyVerifier;

    @Autowired
    RoomDeliveryRuntimeService deliveryRuntimeService;

    @Autowired
    PoisonOperationService poisonOperationService;

    @Autowired
    RoomRepairService repairService;

    @Autowired
    OwnershipFencingAuditService ownershipFencingAuditService;

    @Autowired
    RoomRuntimeOverviewService runtimeOverviewService;

    @Autowired
    TextConvergenceService textConvergenceService;

    OperationSubmitResult insert(Fixture fixture, String operationId, long clientSeq, long baseRevision, String anchor, String content) {
        OperationSubmitResult result = submitText(fixture, operationId, clientSeq, baseRevision, "TEXT_INSERT_AFTER",
                "START".equals(anchor)
                        ? Map.of("text", content)
                        : Map.of("anchorAtomId", anchor, "text", content));
        assertThat(result.accepted()).isTrue();
        return result;
    }

    OperationSubmitResult delete(Fixture fixture, String operationId, long clientSeq, long baseRevision, String atomId) {
        OperationSubmitResult result = submitText(fixture, operationId, clientSeq, baseRevision, "TEXT_DELETE_ATOMS",
                Map.of("atomIds", java.util.List.of(atomId)));
        assertThat(result.accepted()).isTrue();
        return result;
    }

    String atomId(String operationId, int spanIndex) {
        return operationId + ":" + spanIndex;
    }

    DocumentSnapshot snapshot(Fixture fixture) {
        return snapshotService.createSnapshot(fixture.roomId(), "MANUAL");
    }

    void corruptDocumentState(Fixture fixture, String content) {
        jdbcTemplate.update("""
                update document_live_states
                set content_text = ?,
                    content_checksum = ?,
                    updated_at = now()
                where room_id = ?
                """, content, documentStateService.checksum(content), fixture.roomId());
    }

    long roomSeqCount(Fixture fixture) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from room_operations where room_id = ?",
                Long.class,
                fixture.roomId());
        return count == null ? 0 : count;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> runtimeGet(Fixture fixture, String path) {
        return getMap("/api/v1/rooms/" + fixture.roomId() + "/runtime" + path + "?userId=" + fixture.ownerId());
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> runtimePost(Fixture fixture, String path, String reason) {
        return postMap("/api/v1/rooms/" + fixture.roomId() + "/runtime" + path + "?userId=" + fixture.ownerId(),
                Map.of("reason", reason));
    }
}
