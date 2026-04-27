package com.syncforge.api.runtime;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.syncforge.api.operation.store.OperationRepository;
import com.syncforge.api.ownership.RoomOwnershipEvent;
import com.syncforge.api.ownership.RoomOwnershipRepository;
import org.springframework.stereotype.Service;

@Service
public class OwnershipFencingAuditService {
    private final RoomOwnershipRepository ownershipRepository;
    private final OperationRepository operationRepository;

    public OwnershipFencingAuditService(RoomOwnershipRepository ownershipRepository, OperationRepository operationRepository) {
        this.ownershipRepository = ownershipRepository;
        this.operationRepository = operationRepository;
    }

    public OwnershipFencingAuditResponse audit(UUID roomId) {
        List<RoomInvariantViolation> violations = new ArrayList<>();
        Long previousToken = null;
        for (RoomOwnershipEvent event : ownershipRepository.events(roomId)) {
            if (!isLifecycleTokenEvent(event.eventType())) {
                continue;
            }
            Long token = event.fencingToken();
            if (token == null) {
                continue;
            }
            if (previousToken != null && token < previousToken) {
                violations.add(new RoomInvariantViolation("FENCING_TOKEN_REGRESSED", "ERROR",
                        "ownership fencing tokens must be monotonic", previousToken.toString(), token.toString(), null));
            }
            previousToken = Math.max(previousToken == null ? token : previousToken, token);
        }
        long missingOwnerMetadata = operationRepository.countMissingOwnerMetadata(roomId);
        if (missingOwnerMetadata > 0) {
            violations.add(new RoomInvariantViolation("OPERATION_OWNER_METADATA_MISSING", "WARN",
                    "accepted operations should include owner metadata", "0", Long.toString(missingOwnerMetadata), null));
        }
        return new OwnershipFencingAuditResponse(
                roomId,
                violations.isEmpty() ? RoomInvariantStatus.PASS : RoomInvariantStatus.FAIL,
                OffsetDateTime.now(),
                violations.size(),
                violations);
    }

    private boolean isLifecycleTokenEvent(String eventType) {
        return "ACQUIRED".equals(eventType)
                || "RENEWED".equals(eventType)
                || "RELEASED".equals(eventType)
                || "EXPIRED".equals(eventType)
                || "TAKEOVER".equals(eventType);
    }
}
