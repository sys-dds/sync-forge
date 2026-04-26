package com.syncforge.api.operation.model;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record OfflineOperationSubmission(
        UUID id,
        UUID roomId,
        UUID userId,
        String clientId,
        String clientOperationId,
        long baseRoomSeq,
        long baseRevision,
        String canonicalPayloadHash,
        List<String> causalDependencies,
        OfflineOperationSubmissionStatus status,
        String acceptedOperationId,
        Long acceptedRoomSeq,
        String rejectionCode,
        String rejectionReason,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
