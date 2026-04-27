package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JepsenLitePermissionRemovalIntegrationTest extends JepsenLiteTestSupport {
    @Test
    void removedUserCannotUseAnyRecoveryRuntimeOrCompactionPath() {
        Fixture fixture = fixture();
        insert(fixture, "perm-a", 1, 0, "START", "A");
        snapshot(fixture);
        assertThat(getMap("/api/v1/rooms/" + fixture.roomId() + "/document-state?userId=" + fixture.viewerId()))
                .containsEntry("contentText", "A");

        jdbcTemplate.update("update room_memberships set status = 'REMOVED' where room_id = ? and user_id = ?",
                fixture.roomId(), fixture.viewerId());

        assertForbiddenGet("/api/v1/rooms/" + fixture.roomId() + "/resume?userId=" + fixture.viewerId() + "&fromRoomSeq=0");
        assertForbiddenGet("/api/v1/rooms/" + fixture.roomId() + "/resume/snapshot-refresh?userId=" + fixture.viewerId());
        assertForbiddenGet("/api/v1/rooms/" + fixture.roomId() + "/document-state?userId=" + fixture.viewerId());
        assertForbiddenGet("/api/v1/rooms/" + fixture.roomId() + "/runtime?userId=" + fixture.viewerId());
        assertForbiddenGet("/api/v1/rooms/" + fixture.roomId() + "/runtime/health?userId=" + fixture.viewerId());
        assertForbiddenGet("/api/v1/rooms/" + fixture.roomId() + "/runtime/invariants?userId=" + fixture.viewerId());
        assertForbiddenGet("/api/v1/rooms/" + fixture.roomId() + "/runtime/delivery?userId=" + fixture.viewerId());
        assertForbiddenGet("/api/v1/rooms/" + fixture.roomId() + "/runtime/ownership-audit?userId=" + fixture.viewerId());
        assertForbiddenGet("/api/v1/rooms/" + fixture.roomId() + "/runtime/poison-operations?userId=" + fixture.viewerId());
        assertForbiddenPost("/api/v1/rooms/" + fixture.roomId() + "/runtime/repair/rebuild-state?userId=" + fixture.viewerId());
        assertForbiddenPost("/api/v1/rooms/" + fixture.roomId() + "/runtime/pause?userId=" + fixture.viewerId());
        assertForbiddenPost("/api/v1/rooms/" + fixture.roomId() + "/runtime/resume-writes?userId=" + fixture.viewerId());
        assertForbiddenPost("/api/v1/rooms/" + fixture.roomId() + "/runtime/force-resync?userId=" + fixture.viewerId());
        assertForbiddenGet("/api/v1/rooms/" + fixture.roomId() + "/operations/compaction?userId=" + fixture.viewerId());

        assertThat(resumeWindowService.decide(fixture.roomId(), fixture.editorId(), 1).decision().name()).isEqualTo("RESUMABLE");
        assertInvariantPass(fixture);
    }
}
