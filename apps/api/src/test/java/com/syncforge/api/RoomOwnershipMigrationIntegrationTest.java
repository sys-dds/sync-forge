package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class RoomOwnershipMigrationIntegrationTest extends RoomOwnershipTestSupport {
    @Test
    void migrationAddsLeaseEventsAndOwnerMetadataConstraints() {
        Fixture fixture = fixture();

        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from information_schema.tables
                where table_name in ('room_ownership_leases', 'room_ownership_events')
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from information_schema.columns
                where table_name = 'room_operations' and column_name in ('owner_node_id', 'fencing_token')
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from information_schema.columns
                where table_name = 'room_event_outbox' and column_name in ('owner_node_id', 'fencing_token')
                """, Integer.class)).isEqualTo(2);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into room_ownership_leases (room_id, owner_node_id, fencing_token, lease_status, lease_expires_at)
                values (?, 'bad-node', 0, 'ACTIVE', now() + interval '30 seconds')
                """, fixture.roomId())).isInstanceOf(DataIntegrityViolationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into room_ownership_events (id, room_id, node_id, event_type)
                values (?, ?, 'bad-node', 'NOT_A_REAL_EVENT')
                """, java.util.UUID.randomUUID(), fixture.roomId())).isInstanceOf(DataIntegrityViolationException.class);
    }
}
