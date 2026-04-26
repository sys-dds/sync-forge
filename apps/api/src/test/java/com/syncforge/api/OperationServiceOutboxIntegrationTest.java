package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Map;

import com.syncforge.api.delivery.RoomEventOutboxRepository;
import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.documentstate.model.DocumentLiveState;
import com.syncforge.api.operation.application.OperationService;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import com.syncforge.api.operation.store.OperationRepository;
import com.syncforge.api.stream.application.RoomEventStreamPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class OperationServiceOutboxIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    OperationService operationService;

    @Autowired
    OperationRepository operationRepository;

    @Autowired
    DocumentStateService documentStateService;

    @Autowired
    RoomEventOutboxRepository outboxRepository;

    @MockitoBean
    RoomEventStreamPublisher streamPublisher;

    @Test
    void acceptedOperationCommitsCanonicalStateAndOutboxWithoutDirectRedisPublish() {
        Fixture fixture = fixture();

        OperationSubmitResult result = operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                fixture.editorId(),
                "service-outbox-connection",
                "service-outbox-session",
                "service-outbox-accepted",
                1L,
                0L,
                "TEXT_INSERT",
                Map.of("position", 0, "text", "truth")));

        assertThat(result.accepted()).isTrue();
        verifyNoInteractions(streamPublisher);
        assertThat(operationRepository.findByRoomAndOperationId(fixture.roomId(), "service-outbox-accepted")).isPresent();
        assertThat(outboxRepository.findByRoomSeq(fixture.roomId(), result.roomSeq())).isPresent();

        DocumentLiveState live = documentStateService.getOrInitialize(fixture.roomId());
        assertThat(live.contentText()).isEqualTo("truth");
        assertThat(live.currentRoomSeq()).isEqualTo(result.roomSeq());
        assertThat(live.currentRevision()).isEqualTo(result.revision());

        DocumentLiveState replay = documentStateService.rebuildFromOperationLog(fixture.roomId()).state();
        assertThat(replay.contentText()).isEqualTo(live.contentText());
        assertThat(replay.currentRoomSeq()).isEqualTo(live.currentRoomSeq());
        assertThat(replay.currentRevision()).isEqualTo(live.currentRevision());
    }
}
