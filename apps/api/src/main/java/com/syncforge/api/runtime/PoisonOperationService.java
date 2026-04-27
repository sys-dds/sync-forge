package com.syncforge.api.runtime;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PoisonOperationService {
    private final PoisonOperationRepository repository;
    private final RoomRuntimeControlService controlService;

    public PoisonOperationService(PoisonOperationRepository repository, RoomRuntimeControlService controlService) {
        this.repository = repository;
        this.controlService = controlService;
    }

    @Transactional
    public PoisonOperationRecord quarantine(UUID roomId, String operationId, Long roomSeq, String reason, UUID actorUserId) {
        PoisonOperationRecord record = repository.quarantine(roomId, operationId, roomSeq, reason);
        controlService.markRepairRequired(roomId, actorUserId, "POISON_OPERATION_QUARANTINED");
        return record;
    }

    public List<PoisonOperationRecord> listQuarantined(UUID roomId) {
        return repository.listQuarantined(roomId);
    }

    public long countQuarantined(UUID roomId) {
        return repository.countQuarantined(roomId);
    }

    public void clear(UUID roomId) {
        repository.clear(roomId);
    }
}
