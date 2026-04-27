package com.syncforge.api.runtime;

import java.util.List;
import java.util.UUID;

import com.syncforge.api.room.application.RoomPermissionService;
import com.syncforge.api.shared.RequestValidator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rooms/{roomId}/runtime")
public class RoomRuntimeController {
    private final RoomPermissionService permissionService;
    private final RoomRuntimeHealthService healthService;
    private final RoomConsistencyVerifier verifier;
    private final RoomRuntimeControlService controlService;
    private final RoomDeliveryRuntimeService deliveryRuntimeService;
    private final PoisonOperationService poisonOperationService;
    private final RoomRepairService repairService;
    private final OwnershipFencingAuditService ownershipFencingAuditService;
    private final RoomRuntimeOverviewService overviewService;

    public RoomRuntimeController(
            RoomPermissionService permissionService,
            RoomRuntimeHealthService healthService,
            RoomConsistencyVerifier verifier,
            RoomRuntimeControlService controlService,
            RoomDeliveryRuntimeService deliveryRuntimeService,
            PoisonOperationService poisonOperationService,
            RoomRepairService repairService,
            OwnershipFencingAuditService ownershipFencingAuditService,
            RoomRuntimeOverviewService overviewService) {
        this.permissionService = permissionService;
        this.healthService = healthService;
        this.verifier = verifier;
        this.controlService = controlService;
        this.deliveryRuntimeService = deliveryRuntimeService;
        this.poisonOperationService = poisonOperationService;
        this.repairService = repairService;
        this.ownershipFencingAuditService = ownershipFencingAuditService;
        this.overviewService = overviewService;
    }

    @GetMapping
    public RoomRuntimeOverviewResponse overview(@PathVariable String roomId, @RequestParam String userId) {
        UUID parsedRoomId = roomId(roomId);
        requireManage(parsedRoomId, userId);
        return overviewService.overview(parsedRoomId);
    }

    @GetMapping("/health")
    public RoomRuntimeHealthResponse health(@PathVariable String roomId, @RequestParam String userId) {
        UUID parsedRoomId = roomId(roomId);
        requireManage(parsedRoomId, userId);
        return healthService.health(parsedRoomId);
    }

    @GetMapping("/invariants")
    public RoomInvariantSnapshot invariants(@PathVariable String roomId, @RequestParam String userId) {
        UUID parsedRoomId = roomId(roomId);
        requireManage(parsedRoomId, userId);
        return verifier.verify(parsedRoomId);
    }

    @PostMapping("/pause")
    public RoomRuntimeControlState pause(
            @PathVariable String roomId,
            @RequestParam String userId,
            @RequestBody(required = false) RuntimeControlRequest request) {
        UUID parsedRoomId = roomId(roomId);
        return controlService.pauseWrites(parsedRoomId, userId(userId), reason(request));
    }

    @PostMapping("/resume-writes")
    public RoomRuntimeControlState resumeWrites(
            @PathVariable String roomId,
            @RequestParam String userId,
            @RequestBody(required = false) RuntimeControlRequest request) {
        UUID parsedRoomId = roomId(roomId);
        return controlService.resumeWrites(parsedRoomId, userId(userId), reason(request));
    }

    @PostMapping("/force-resync")
    public RoomRuntimeControlState forceResync(
            @PathVariable String roomId,
            @RequestParam String userId,
            @RequestBody(required = false) RuntimeControlRequest request) {
        UUID parsedRoomId = roomId(roomId);
        return controlService.forceResync(parsedRoomId, userId(userId), reason(request));
    }

    @GetMapping("/delivery")
    public RoomDeliveryRuntimeResponse delivery(@PathVariable String roomId, @RequestParam String userId) {
        UUID parsedRoomId = roomId(roomId);
        requireManage(parsedRoomId, userId);
        return deliveryRuntimeService.status(parsedRoomId);
    }

    @PostMapping("/delivery/drain")
    public RoomDeliveryDrainResponse drainDelivery(@PathVariable String roomId, @RequestParam String userId) {
        UUID parsedRoomId = roomId(roomId);
        requireManage(parsedRoomId, userId);
        return deliveryRuntimeService.drain(parsedRoomId);
    }

    @GetMapping("/poison-operations")
    public List<PoisonOperationRecord> poisonOperations(@PathVariable String roomId, @RequestParam String userId) {
        UUID parsedRoomId = roomId(roomId);
        requireManage(parsedRoomId, userId);
        return poisonOperationService.listQuarantined(parsedRoomId);
    }

    @PostMapping("/repair/rebuild-state")
    public RoomRepairRebuildResponse rebuildState(
            @PathVariable String roomId,
            @RequestParam String userId,
            @RequestBody(required = false) RuntimeControlRequest request) {
        UUID parsedRoomId = roomId(roomId);
        return repairService.rebuildState(parsedRoomId, userId(userId), reason(request));
    }

    @GetMapping("/ownership-audit")
    public OwnershipFencingAuditResponse ownershipAudit(@PathVariable String roomId, @RequestParam String userId) {
        UUID parsedRoomId = roomId(roomId);
        requireManage(parsedRoomId, userId);
        return ownershipFencingAuditService.audit(parsedRoomId);
    }

    private void requireManage(UUID roomId, String userId) {
        permissionService.requireManageMembers(roomId, userId(userId));
    }

    private UUID roomId(String roomId) {
        return RequestValidator.parseUuid(roomId, "roomId");
    }

    private UUID userId(String userId) {
        return RequestValidator.parseUuid(userId, "userId");
    }

    private String reason(RuntimeControlRequest request) {
        return request == null ? null : request.reason();
    }
}
