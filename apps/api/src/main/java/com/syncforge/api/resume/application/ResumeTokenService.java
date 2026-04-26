package com.syncforge.api.resume.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import com.syncforge.api.resume.model.IssuedResumeToken;
import com.syncforge.api.resume.model.ResumeToken;
import com.syncforge.api.resume.store.ResumeTokenRepository;
import com.syncforge.api.room.application.RoomPermissionService;
import com.syncforge.api.shared.ForbiddenException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ResumeTokenService {
    private final ResumeTokenRepository resumeTokenRepository;
    private final RoomPermissionService permissionService;
    private final SecureRandom secureRandom = new SecureRandom();
    private final long tokenTtlSeconds;

    public ResumeTokenService(
            ResumeTokenRepository resumeTokenRepository,
            RoomPermissionService permissionService,
            @Value("${syncforge.resume.token-ttl-seconds:3600}") long tokenTtlSeconds) {
        this.resumeTokenRepository = resumeTokenRepository;
        this.permissionService = permissionService;
        this.tokenTtlSeconds = tokenTtlSeconds;
    }

    public IssuedResumeToken issue(UUID roomId, UUID userId, String connectionId, String clientSessionId, long lastSeenRoomSeq) {
        permissionService.requireJoin(roomId, userId);
        byte[] raw = new byte[32];
        secureRandom.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        OffsetDateTime now = OffsetDateTime.now();
        ResumeToken record = resumeTokenRepository.create(roomId, userId, connectionId, clientSessionId, hash(token),
                now, now.plusSeconds(tokenTtlSeconds), lastSeenRoomSeq);
        return new IssuedResumeToken(token, record);
    }

    public ResumeToken validate(String token, UUID roomId, UUID userId, String clientSessionId) {
        if (token == null || token.isBlank()) {
            throw new ForbiddenException("INVALID_RESUME_TOKEN", "resume token is required");
        }
        ResumeToken record = resumeTokenRepository.findByHash(hash(token))
                .orElseThrow(() -> new ForbiddenException("INVALID_RESUME_TOKEN", "resume token is invalid"));
        if (!record.roomId().equals(roomId) || !record.userId().equals(userId)) {
            throw new ForbiddenException("INVALID_RESUME_TOKEN", "resume token scope does not match room/user");
        }
        if (record.clientSessionId() != null && clientSessionId != null && !record.clientSessionId().equals(clientSessionId)) {
            throw new ForbiddenException("INVALID_RESUME_TOKEN", "resume token scope does not match client session");
        }
        if (record.revokedAt() != null || !record.expiresAt().isAfter(OffsetDateTime.now())) {
            throw new ForbiddenException("RESUME_TOKEN_EXPIRED", "resume token has expired");
        }
        permissionService.requireJoin(roomId, userId);
        return record;
    }

    public void updateLastSeen(String token, long lastSeenRoomSeq) {
        resumeTokenRepository.updateLastSeen(hash(token), lastSeenRoomSeq);
    }

    public String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
