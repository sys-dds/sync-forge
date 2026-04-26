package com.syncforge.api.resume.application;

import java.util.UUID;

import com.syncforge.api.resume.store.ClientOffsetRepository;
import org.springframework.stereotype.Service;

@Service
public class ClientOffsetService {
    private final ClientOffsetRepository clientOffsetRepository;

    public ClientOffsetService(ClientOffsetRepository clientOffsetRepository) {
        this.clientOffsetRepository = clientOffsetRepository;
    }

    public void acknowledge(UUID roomId, UUID userId, String clientSessionId, long roomSeq) {
        if (clientSessionId == null || clientSessionId.isBlank()) {
            return;
        }
        clientOffsetRepository.upsert(roomId, userId, clientSessionId, roomSeq);
    }

    public long lastSeenOrDefault(UUID roomId, UUID userId, String clientSessionId, long fallback) {
        if (clientSessionId == null || clientSessionId.isBlank()) {
            return fallback;
        }
        return clientOffsetRepository.find(roomId, userId, clientSessionId).orElse(fallback);
    }
}
