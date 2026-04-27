package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.syncforge.api.operation.application.OperationCompactionService;
import com.syncforge.api.snapshot.application.SnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

class ResumeContractIntegrationTest extends TextConvergenceTestSupport {
    @Autowired
    SnapshotService snapshotService;

    @Autowired
    OperationCompactionService compactionService;

    @Test
    @SuppressWarnings("unchecked")
    void resumeResponsesExposeStableDecisionMetadataAndNeverReturnCompactedHistory() {
        Fixture fixture = fixture();
        submitAcceptedText(fixture, "resume-contract-a", 1, 0, "TEXT_INSERT_AFTER", Map.of("text", "A"));
        snapshotService.createSnapshot(fixture.roomId(), "MANUAL");
        submitAcceptedText(fixture, "resume-contract-b", 2, 1, "TEXT_INSERT_AFTER",
                Map.of("anchorAtomId", "resume-contract-a:0", "text", "B"));
        compactionService.compactSafeHistory(fixture.roomId());

        Map<String, Object> valid = getMap("/api/v1/rooms/" + fixture.roomId()
                + "/resume?userId=" + fixture.editorId() + "&fromRoomSeq=1");
        Map<String, Object> stale = getMap("/api/v1/rooms/" + fixture.roomId()
                + "/resume?userId=" + fixture.editorId() + "&fromRoomSeq=0");
        Map<String, Object> latest = getMap("/api/v1/rooms/" + fixture.roomId()
                + "/resume?userId=" + fixture.editorId() + "&fromRoomSeq=2");
        ResponseEntity<Map> negative = restTemplate.getForEntity(baseUrl + "/api/v1/rooms/" + fixture.roomId()
                + "/resume?userId=" + fixture.editorId() + "&fromRoomSeq=-1", Map.class);
        ResponseEntity<Map> ahead = restTemplate.getForEntity(baseUrl + "/api/v1/rooms/" + fixture.roomId()
                + "/resume?userId=" + fixture.editorId() + "&fromRoomSeq=3", Map.class);
        ResponseEntity<Map> outsider = restTemplate.getForEntity(baseUrl + "/api/v1/rooms/" + fixture.roomId()
                + "/resume?userId=" + fixture.outsiderId() + "&fromRoomSeq=1", Map.class);
        removeMember(fixture.viewerId(), fixture.roomId());
        ResponseEntity<Map> removed = restTemplate.getForEntity(baseUrl + "/api/v1/rooms/" + fixture.roomId()
                + "/resume?userId=" + fixture.viewerId() + "&fromRoomSeq=1", Map.class);

        assertThat(valid).containsEntry("decision", "RESUMABLE")
                .containsEntry("roomId", fixture.roomId().toString())
                .containsEntry("requestedFromRoomSeq", 1)
                .containsEntry("fromRoomSeq", 1)
                .containsEntry("toRoomSeq", 2)
                .containsEntry("minimumResumableRoomSeq", 1)
                .containsEntry("snapshotRoomSeq", 1)
                .containsEntry("latestRoomSeq", 2)
                .containsEntry("returnedOperationCount", 1);
        assertThat((List<Map<String, Object>>) valid.get("operations"))
                .extracting(operation -> operation.get("operationId"))
                .containsExactly("resume-contract-b");

        assertThat(stale).containsEntry("decision", "REFRESH_REQUIRED")
                .containsEntry("reason", "CLIENT_BEHIND_MINIMUM_RESUMABLE_SEQUENCE")
                .containsEntry("requestedFromRoomSeq", 0)
                .containsEntry("returnedOperationCount", 0);
        assertThat((List<Map<String, Object>>) stale.get("operations")).isEmpty();
        assertThat(latest).containsEntry("decision", "RESUMABLE")
                .containsEntry("returnedOperationCount", 0);
        assertThat((List<Map<String, Object>>) latest.get("operations")).isEmpty();
        assertThat(negative.getStatusCode().is4xxClientError()).isTrue();
        assertThat(ahead.getStatusCode().is4xxClientError()).isTrue();
        assertThat(outsider.getStatusCode().is4xxClientError()).isTrue();
        assertThat(removed.getStatusCode().is4xxClientError()).isTrue();
    }

    private void removeMember(java.util.UUID userId, java.util.UUID roomId) {
        jdbcTemplate.update("""
                update room_memberships
                set status = 'REMOVED'
                where room_id = ? and user_id = ?
                """, roomId, userId);
    }
}
