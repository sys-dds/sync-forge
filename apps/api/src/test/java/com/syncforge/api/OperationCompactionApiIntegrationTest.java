package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.syncforge.api.snapshot.application.SnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

class OperationCompactionApiIntegrationTest extends TextConvergenceTestSupport {
    @Autowired
    SnapshotService snapshotService;

    @Test
    void ownerCanPreviewAndRunCompactionWhileOtherUsersAreDenied() {
        Fixture fixture = fixture();
        submitAcceptedText(fixture, "compaction-api-a", 1, 0, "TEXT_INSERT_AFTER", Map.of("text", "A"));

        Map<String, Object> noSnapshotPreview = getMap("/api/v1/rooms/" + fixture.roomId()
                + "/operations/compaction?userId=" + fixture.ownerId());
        ResponseEntity<Map> editorPreview = restTemplate.getForEntity(baseUrl + "/api/v1/rooms/" + fixture.roomId()
                + "/operations/compaction?userId=" + fixture.editorId(), Map.class);
        ResponseEntity<Map> viewerPreview = restTemplate.getForEntity(baseUrl + "/api/v1/rooms/" + fixture.roomId()
                + "/operations/compaction?userId=" + fixture.viewerId(), Map.class);
        ResponseEntity<Map> outsiderPreview = restTemplate.getForEntity(baseUrl + "/api/v1/rooms/" + fixture.roomId()
                + "/operations/compaction?userId=" + fixture.outsiderId(), Map.class);

        assertThat(noSnapshotPreview).containsEntry("safeToCompact", false)
                .containsEntry("reason", "NO_SNAPSHOT_BOUNDARY")
                .containsEntry("compactableOperationCount", 0);
        assertThat(editorPreview.getStatusCode().is4xxClientError()).isTrue();
        assertThat(viewerPreview.getStatusCode().is4xxClientError()).isTrue();
        assertThat(outsiderPreview.getStatusCode().is4xxClientError()).isTrue();

        snapshotService.createSnapshot(fixture.roomId(), "MANUAL");
        submitAcceptedText(fixture, "compaction-api-b", 2, 1, "TEXT_INSERT_AFTER",
                Map.of("anchorAtomId", "compaction-api-a:0", "text", "B"));

        Map<String, Object> preview = getMap("/api/v1/rooms/" + fixture.roomId()
                + "/operations/compaction?userId=" + fixture.ownerId());
        Map<String, Object> run = postMap("/api/v1/rooms/" + fixture.roomId()
                + "/operations/compaction?userId=" + fixture.ownerId(), Map.of());
        Map<String, Object> after = getMap("/api/v1/rooms/" + fixture.roomId()
                + "/operations/compaction?userId=" + fixture.ownerId());
        Map<String, Object> second = postMap("/api/v1/rooms/" + fixture.roomId()
                + "/operations/compaction?userId=" + fixture.ownerId(), Map.of());

        assertThat(preview).containsEntry("latestRoomSeq", 2)
                .containsEntry("snapshotRoomSeq", 1)
                .containsEntry("minimumResumableRoomSeq", 1)
                .containsEntry("compactedOperationCount", 0)
                .containsEntry("activeTailCount", 1)
                .containsEntry("compactableOperationCount", 1)
                .containsEntry("safeToCompact", true)
                .containsEntry("reason", "SNAPSHOT_BOUNDARY_HAS_COMPACTABLE_HISTORY");
        assertThat(preview).doesNotContainKey("operations");
        assertThat(run).containsEntry("roomId", fixture.roomId().toString())
                .containsEntry("minimumResumableRoomSeq", 1)
                .containsEntry("snapshotRoomSeq", 1)
                .containsEntry("compactedCount", 1)
                .containsEntry("activeTailCount", 1)
                .containsEntry("status", "COMPLETED")
                .containsEntry("message", "COMPACTED_SAFE_HISTORY");
        assertThat(after).containsEntry("compactedOperationCount", 1)
                .containsEntry("compactableOperationCount", 0)
                .containsEntry("safeToCompact", false);
        assertThat(after.get("lastCompactionRun")).isNotNull();
        assertThat(second).containsEntry("compactedCount", 0)
                .containsEntry("message", "NO_COMPACTABLE_OPERATIONS");
    }
}
