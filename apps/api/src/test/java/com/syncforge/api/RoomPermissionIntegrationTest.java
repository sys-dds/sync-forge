package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import com.syncforge.api.room.application.RoomPermissionService;
import com.syncforge.api.shared.ForbiddenException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class RoomPermissionIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    RoomPermissionService permissionService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void rolePermissionsAreEnforced() {
        Fixture fixture = fixture();

        assertThat(permissionService.canJoin(fixture.roomId(), fixture.ownerId())).isTrue();
        assertThat(permissionService.canView(fixture.roomId(), fixture.ownerId())).isTrue();
        assertThat(permissionService.canEdit(fixture.roomId(), fixture.ownerId())).isTrue();
        assertThat(permissionService.canManageMembers(fixture.roomId(), fixture.ownerId())).isTrue();

        assertThat(permissionService.canJoin(fixture.roomId(), fixture.editorId())).isTrue();
        assertThat(permissionService.canView(fixture.roomId(), fixture.editorId())).isTrue();
        assertThat(permissionService.canEdit(fixture.roomId(), fixture.editorId())).isTrue();
        assertThat(permissionService.canManageMembers(fixture.roomId(), fixture.editorId())).isFalse();

        assertThat(permissionService.canJoin(fixture.roomId(), fixture.viewerId())).isTrue();
        assertThat(permissionService.canView(fixture.roomId(), fixture.viewerId())).isTrue();
        assertThat(permissionService.canEdit(fixture.roomId(), fixture.viewerId())).isFalse();
        assertThat(permissionService.canManageMembers(fixture.roomId(), fixture.viewerId())).isFalse();

        assertThat(permissionService.canJoin(fixture.roomId(), fixture.outsiderId())).isFalse();
        assertThat(permissionService.canView(fixture.roomId(), fixture.outsiderId())).isFalse();
        assertThat(permissionService.canEdit(fixture.roomId(), fixture.outsiderId())).isFalse();
        assertThat(permissionService.canManageMembers(fixture.roomId(), fixture.outsiderId())).isFalse();
    }

    @Test
    void removedMembershipAndRequireMethodsRejectClearly() {
        Fixture fixture = fixture();
        jdbcTemplate.update("update room_memberships set status = 'REMOVED' where room_id = ? and user_id = ?",
                fixture.roomId(), fixture.viewerId());

        assertThat(permissionService.canJoin(fixture.roomId(), fixture.viewerId())).isFalse();
        assertThatThrownBy(() -> permissionService.requireJoin(fixture.roomId(), fixture.viewerId()))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("join");
        assertThatThrownBy(() -> permissionService.requireEdit(fixture.roomId(), fixture.viewerId()))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("edit");
        assertThatThrownBy(() -> permissionService.requireManageMembers(fixture.roomId(), fixture.editorId()))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("manage");
        permissionService.requireView(fixture.roomId(), fixture.ownerId());
    }

    @Test
    void duplicateMembershipUpdatesRoleAndStatus() {
        Fixture fixture = fixture();
        jdbcTemplate.update("update room_memberships set status = 'REMOVED' where room_id = ? and user_id = ?",
                fixture.roomId(), fixture.viewerId());

        Map<String, Object> response = postMap("/api/v1/rooms/" + fixture.roomId() + "/memberships",
                Map.of("userId", fixture.viewerId().toString(), "role", "EDITOR"));

        assertThat(response).containsEntry("role", "EDITOR").containsEntry("status", "ACTIVE");
        assertThat(permissionService.canEdit(fixture.roomId(), fixture.viewerId())).isTrue();
    }
}
