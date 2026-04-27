package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.snapshot.application.SnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

class SnapshotRefreshContractIntegrationTest extends TextConvergenceTestSupport {
    @Autowired
    SnapshotService snapshotService;

    @Autowired
    DocumentStateService documentStateService;

    @Test
    void snapshotRefreshReturnsLatestBaselineWithoutHistoryOrTombstonedText() {
        Fixture fixture = fixture();
        submitAcceptedText(fixture, "refresh-a", 1, 0, "TEXT_INSERT_AFTER", Map.of("text", "A"));
        snapshotService.createSnapshot(fixture.roomId(), "MANUAL");
        submitAcceptedText(fixture, "refresh-b", 2, 1, "TEXT_INSERT_AFTER",
                Map.of("anchorAtomId", "refresh-a:0", "text", "B"));
        submitAcceptedText(fixture, "refresh-delete-a", 3, 2, "TEXT_DELETE_ATOMS",
                Map.of("atomIds", List.of("refresh-a:0")));

        Map<String, Object> refresh = getMap("/api/v1/rooms/" + fixture.roomId()
                + "/resume/snapshot-refresh?userId=" + fixture.editorId());
        ResponseEntity<Map> outsider = restTemplate.getForEntity(baseUrl + "/api/v1/rooms/" + fixture.roomId()
                + "/resume/snapshot-refresh?userId=" + fixture.outsiderId(), Map.class);
        removeMember(fixture.viewerId(), fixture.roomId());
        ResponseEntity<Map> removed = restTemplate.getForEntity(baseUrl + "/api/v1/rooms/" + fixture.roomId()
                + "/resume/snapshot-refresh?userId=" + fixture.viewerId(), Map.class);

        assertThat(refresh).containsEntry("roomId", fixture.roomId().toString())
                .containsEntry("minimumResumableRoomSeq", 1)
                .containsEntry("snapshotRoomSeq", 1)
                .containsEntry("latestRoomSeq", 3)
                .containsEntry("baselineRoomSeq", 3)
                .containsEntry("visibleText", "B")
                .containsEntry("contentChecksum", documentStateService.checksum("B"))
                .containsEntry("refreshReason", "LATEST_SAFE_BASELINE")
                .containsEntry("nextResumeFromRoomSeq", 3);
        assertThat(refresh).doesNotContainKey("operations");
        assertThat(refresh.get("visibleText")).asString().doesNotContain("A");
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
