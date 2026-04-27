package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ForceRoomResyncIntegrationTest extends RuntimeControlTestSupport {
    @Test
    void forceResyncIncrementsGenerationAndRuntimeHealthReportsIt() {
        Fixture fixture = fixture();

        var first = runtimeControlService.forceResync(fixture.roomId(), fixture.ownerId(), "client baseline invalid");
        var second = runtimeControlService.forceResync(fixture.roomId(), fixture.ownerId(), "client baseline invalid again");

        assertThat(second.forceResyncGeneration()).isEqualTo(first.forceResyncGeneration() + 1);
        assertThat(runtimeHealthService.health(fixture.roomId()).forceResyncGeneration()).isEqualTo(2);
    }
}
