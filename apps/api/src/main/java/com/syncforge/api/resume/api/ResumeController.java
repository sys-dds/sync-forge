package com.syncforge.api.resume.api;

import java.util.UUID;

import com.syncforge.api.resume.application.ResumeWindowService;
import com.syncforge.api.resume.model.ResumeDecision;
import com.syncforge.api.resume.model.SnapshotRefresh;
import com.syncforge.api.shared.RequestValidator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rooms/{roomId}/resume")
public class ResumeController {
    private final ResumeWindowService resumeWindowService;

    public ResumeController(ResumeWindowService resumeWindowService) {
        this.resumeWindowService = resumeWindowService;
    }

    @GetMapping
    public ResumeDecision resume(
            @PathVariable String roomId,
            @RequestParam String userId,
            @RequestParam long fromRoomSeq) {
        UUID parsedRoomId = RequestValidator.parseUuid(roomId, "roomId");
        UUID parsedUserId = RequestValidator.parseUuid(userId, "userId");
        return resumeWindowService.decide(parsedRoomId, parsedUserId, fromRoomSeq);
    }

    @GetMapping("/snapshot-refresh")
    public SnapshotRefresh snapshotRefresh(@PathVariable String roomId, @RequestParam String userId) {
        UUID parsedRoomId = RequestValidator.parseUuid(roomId, "roomId");
        UUID parsedUserId = RequestValidator.parseUuid(userId, "userId");
        return resumeWindowService.snapshotRefresh(parsedRoomId, parsedUserId);
    }
}
