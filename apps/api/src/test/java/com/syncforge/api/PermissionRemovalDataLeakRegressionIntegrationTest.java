package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PermissionRemovalDataLeakRegressionIntegrationTest extends RuntimeControlTestSupport {
    @Test
    void removedUserCannotUseRecoveryOrRuntimePathsWhileAuthorizedUserStillCan() {
        Fixture fixture = fixture();
        insert(fixture, "leak-a", 1, 0, "START", "A");
        snapshot(fixture);
        jdbcTemplate.update("update room_memberships set status = 'REMOVED' where room_id = ? and user_id = ?",
                fixture.roomId(), fixture.viewerId());

        assertForbidden("/api/v1/rooms/" + fixture.roomId() + "/resume?userId=" + fixture.viewerId() + "&fromRoomSeq=0");
        assertForbidden("/api/v1/rooms/" + fixture.roomId() + "/resume/snapshot-refresh?userId=" + fixture.viewerId());
        assertForbidden("/api/v1/rooms/" + fixture.roomId() + "/document-state?userId=" + fixture.viewerId());
        assertForbidden("/api/v1/rooms/" + fixture.roomId() + "/runtime/health?userId=" + fixture.viewerId());
        assertForbidden("/api/v1/rooms/" + fixture.roomId() + "/runtime/invariants?userId=" + fixture.viewerId());
        assertForbidden("/api/v1/rooms/" + fixture.roomId() + "/runtime/delivery?userId=" + fixture.viewerId());
        assertForbidden("/api/v1/rooms/" + fixture.roomId() + "/runtime/ownership-audit?userId=" + fixture.viewerId());

        assertThat(resumeWindowService.decide(fixture.roomId(), fixture.editorId(), 1).decision().name()).isEqualTo("RESUMABLE");
        assertThat(getMap("/api/v1/rooms/" + fixture.roomId() + "/document-state?userId=" + fixture.editorId()))
                .containsEntry("contentText", "A");
    }

    private void assertForbidden(String path) {
        assertThat(restTemplate.getForEntity(baseUrl + path, String.class).getStatusCode().value()).isEqualTo(403);
    }
}
