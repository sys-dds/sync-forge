package com.syncforge.api.text.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TextAtom(
        UUID roomId,
        String atomId,
        String operationId,
        long roomSeq,
        long revision,
        int spanIndex,
        String anchorAtomId,
        String content,
        String orderingKey,
        boolean tombstoned,
        String deletedByOperationId,
        Long deletedAtRoomSeq,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
