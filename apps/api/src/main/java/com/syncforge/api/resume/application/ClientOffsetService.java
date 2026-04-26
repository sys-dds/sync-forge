package com.syncforge.api.resume.application;

import java.util.UUID;

import com.syncforge.api.operation.store.OperationRepository;
import com.syncforge.api.resume.store.ClientOffsetRepository;
import org.springframework.stereotype.Service;

@Service
public class ClientOffsetService {
    private final ClientOffsetRepository clientOffsetRepository;
    private final OperationRepository operationRepository;

    public ClientOffsetService(ClientOffsetRepository clientOffsetRepository, OperationRepository operationRepository) {
        this.clientOffsetRepository = clientOffsetRepository;
        this.operationRepository = operationRepository;
    }

    public boolean acknowledge(UUID roomId, UUID userId, String clientSessionId, long roomSeq) {
        if (clientSessionId == null || clientSessionId.isBlank()) {
            return true;
        }
        long maxAcceptedRoomSeq = operationRepository.maxRoomSeq(roomId);
        if (roomSeq > maxAcceptedRoomSeq) {
            return false;
        }
        clientOffsetRepository.upsert(roomId, userId, clientSessionId, roomSeq);
        return true;
    }

    public long lastSeenOrDefault(UUID roomId, UUID userId, String clientSessionId, long fallback) {
        if (clientSessionId == null || clientSessionId.isBlank()) {
            return fallback;
        }
        return clientOffsetRepository.find(roomId, userId, clientSessionId).orElse(fallback);
    }
}
