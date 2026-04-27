package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import com.syncforge.api.resume.application.ResumeWindowService;
import com.syncforge.api.resume.model.ResumeDecision;
import com.syncforge.api.resume.model.ResumeDecisionType;
import com.syncforge.api.room.application.RoomPermissionService;
import com.syncforge.api.snapshot.application.SnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ResumeWindowIntegrationTest extends TextConvergenceTestSupport {
    @Autowired
    ResumeWindowService resumeWindowService;

    @Autowired
    SnapshotService snapshotService;

    @Autowired
    RoomPermissionService permissionService;

    @Test
    void resumeFromLatestReturnsEmptyTailAndMetadata() {
        Fixture fixture = fixture();
        submitAcceptedText(fixture, "resume-latest-a", 1, 0, "TEXT_INSERT_AFTER", Map.of("text", "A"));
        snapshotService.createSnapshot(fixture.roomId(), "MANUAL");

        ResumeDecision decision = resumeWindowService.decide(fixture.roomId(), fixture.editorId(), 1);

        assertThat(decision.decision()).isEqualTo(ResumeDecisionType.RESUMABLE);
        assertThat(decision.operations()).isEmpty();
        assertThat(decision.latestRoomSeq()).isEqualTo(1);
        assertThat(decision.minimumResumableRoomSeq()).isEqualTo(1);
        assertThat(decision.snapshotRoomSeq()).isEqualTo(1);
    }

    @Test
    void resumeFromSnapshotBoundaryReturnsBoundedTail() {
        Fixture fixture = fixture();
        submitAcceptedText(fixture, "resume-boundary-a", 1, 0, "TEXT_INSERT_AFTER", Map.of("text", "A"));
        snapshotService.createSnapshot(fixture.roomId(), "MANUAL");
        submitAcceptedText(fixture, "resume-boundary-b", 2, 1, "TEXT_INSERT_AFTER",
                Map.of("anchorAtomId", "resume-boundary-a:0", "text", "B"));

        ResumeDecision decision = resumeWindowService.decide(fixture.roomId(), fixture.editorId(), 1);

        assertThat(decision.decision()).isEqualTo(ResumeDecisionType.RESUMABLE);
        assertThat(decision.operations()).hasSize(1);
        assertThat(decision.fromRoomSeq()).isEqualTo(1);
        assertThat(decision.toRoomSeq()).isEqualTo(2);
        assertThat(decision.operations().getFirst()).containsEntry("operationId", "resume-boundary-b");
    }

    @Test
    void resumeBeforeMinimumRequiresRefreshAndUnauthorizedUserIsDenied() {
        Fixture fixture = fixture();
        submitAcceptedText(fixture, "resume-stale-a", 1, 0, "TEXT_INSERT_AFTER", Map.of("text", "A"));
        snapshotService.createSnapshot(fixture.roomId(), "MANUAL");
        submitAcceptedText(fixture, "resume-stale-b", 2, 1, "TEXT_INSERT_AFTER",
                Map.of("anchorAtomId", "resume-stale-a:0", "text", "B"));

        ResumeDecision stale = resumeWindowService.decide(fixture.roomId(), fixture.editorId(), 0);

        assertThat(stale.decision()).isEqualTo(ResumeDecisionType.REFRESH_REQUIRED);
        assertThat(stale.reason()).isEqualTo("CLIENT_BEHIND_MINIMUM_RESUMABLE_SEQUENCE");
        assertThat(stale.minimumResumableRoomSeq()).isEqualTo(1);
        assertThat(stale.latestRoomSeq()).isEqualTo(2);
        assertThatThrownBy(() -> resumeWindowService.decide(fixture.roomId(), fixture.outsiderId(), 1))
                .hasMessageContaining("not allowed to view room");
    }
}
