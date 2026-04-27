package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class PauseResumeRoomWritesIntegrationTest extends RuntimeControlTestSupport {
    @Test
    void pausedRoomBlocksSubmitBeforeMutationOutboxOrRoomSeqThenResumeAllowsWrites() {
        Fixture fixture = fixture();
        runtimeControlService.pauseWrites(fixture.roomId(), fixture.ownerId(), "TEST");

        var rejected = submitText(fixture, "paused-a", 1, 0, "TEXT_INSERT_AFTER",
                Map.of("anchorAtomId", "START", "content", "A"));

        assertThat(rejected.accepted()).isFalse();
        assertThat(rejected.code()).isEqualTo("ROOM_WRITES_PAUSED");
        assertThat(roomSeqCount(fixture)).isZero();
        assertThat(outboxCount(fixture.roomId())).isZero();

        runtimeControlService.resumeWrites(fixture.roomId(), fixture.ownerId(), "TEST");
        insert(fixture, "paused-b", 2, 0, "START", "B");
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo("B");
    }
}
