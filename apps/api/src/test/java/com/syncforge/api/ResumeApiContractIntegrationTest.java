package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.syncforge.api.operation.application.OperationCompactionService;
import com.syncforge.api.snapshot.application.SnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

class ResumeApiContractIntegrationTest extends TextConvergenceTestSupport {
    @Autowired
    SnapshotService snapshotService;

    @Autowired
    OperationCompactionService compactionService;

    @Test
    @SuppressWarnings("unchecked")
    void resumeApiReturnsTailRefreshRequiredUnauthorizedAndSnapshotRefreshContracts() {
        Fixture fixture = fixture();
        submitAcceptedText(fixture, "resume-api-a", 1, 0, "TEXT_INSERT_AFTER", Map.of("text", "A"));
        snapshotService.createSnapshot(fixture.roomId(), "MANUAL");
        submitAcceptedText(fixture, "resume-api-b", 2, 1, "TEXT_INSERT_AFTER",
                Map.of("anchorAtomId", "resume-api-a:0", "text", "B"));
        compactionService.compactSafeHistory(fixture.roomId());

        Map<String, Object> resumable = getMap("/api/v1/rooms/" + fixture.roomId()
                + "/resume?userId=" + fixture.editorId() + "&fromRoomSeq=1");
        Map<String, Object> stale = getMap("/api/v1/rooms/" + fixture.roomId()
                + "/resume?userId=" + fixture.editorId() + "&fromRoomSeq=0");
        Map<String, Object> latest = getMap("/api/v1/rooms/" + fixture.roomId()
                + "/resume?userId=" + fixture.editorId() + "&fromRoomSeq=2");
        Map<String, Object> refresh = getMap("/api/v1/rooms/" + fixture.roomId()
                + "/resume/snapshot-refresh?userId=" + fixture.editorId());
        ResponseEntity<Map> unauthorized = restTemplate.getForEntity(baseUrl + "/api/v1/rooms/" + fixture.roomId()
                + "/resume?userId=" + fixture.outsiderId() + "&fromRoomSeq=1", Map.class);

        assertThat(resumable).containsEntry("decision", "RESUMABLE");
        assertThat((List<Map<String, Object>>) resumable.get("operations")).hasSize(1);
        assertThat((Integer) resumable.get("minimumResumableRoomSeq")).isEqualTo(1);
        assertThat(stale).containsEntry("decision", "REFRESH_REQUIRED");
        assertThat(stale).containsEntry("reason", "CLIENT_BEHIND_MINIMUM_RESUMABLE_SEQUENCE");
        assertThat((List<Map<String, Object>>) latest.get("operations")).isEmpty();
        assertThat(refresh).containsEntry("visibleText", "AB");
        assertThat(refresh).containsEntry("snapshotRoomSeq", 1);
        assertThat(unauthorized.getStatusCode().is4xxClientError()).isTrue();
    }
}
