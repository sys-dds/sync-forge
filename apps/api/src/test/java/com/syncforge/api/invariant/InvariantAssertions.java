package com.syncforge.api.invariant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.syncforge.api.documentstate.application.DocumentStateService;
import org.springframework.jdbc.core.JdbcTemplate;

public final class InvariantAssertions {
    private InvariantAssertions() {
    }

    public static void assertNoDuplicateAcceptedOperations(JdbcTemplate jdbcTemplate, UUID roomId) {
        Long duplicateCount = jdbcTemplate.queryForObject("""
                select count(*)
                from (
                    select operation_id
                    from room_operations
                    where room_id = ?
                    group by operation_id
                    having count(*) > 1
                ) duplicates
                """, Long.class, roomId);
        assertThat(duplicateCount).isZero();
    }

    public static void assertGaplessRoomSeq(JdbcTemplate jdbcTemplate, UUID roomId) {
        List<Long> roomSeqs = jdbcTemplate.queryForList("""
                select room_seq
                from room_operations
                where room_id = ?
                order by room_seq
                """, Long.class, roomId);
        for (int i = 0; i < roomSeqs.size(); i++) {
            assertThat(roomSeqs.get(i)).isEqualTo(i + 1L);
        }
    }

    public static void assertReplayEqualsLive(DocumentStateService documentStateService, UUID roomId) {
        assertThat(documentStateService.verifyFullReplayEquivalence(roomId)).isTrue();
    }

    public static void assertAllAckedOperationsAppearInLog(JdbcTemplate jdbcTemplate, UUID roomId, List<String> operationIds) {
        for (String operationId : operationIds) {
            Long count = jdbcTemplate.queryForObject("""
                    select count(*)
                    from room_operations
                    where room_id = ? and operation_id = ?
                    """, Long.class, roomId, operationId);
            assertThat(count).isEqualTo(1L);
        }
    }
}
