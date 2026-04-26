package com.syncforge.api.operation.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;

@Service
public class CanonicalOperationPayloadHasher {
    private final ObjectMapper canonicalMapper;

    public CanonicalOperationPayloadHasher(ObjectMapper objectMapper) {
        this.canonicalMapper = objectMapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public String hash(String operationType, Map<String, Object> operation) {
        try {
            byte[] canonical = canonicalMapper.writeValueAsBytes(Map.of(
                    "operationType", operationType,
                    "operation", operation));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to canonicalize operation payload", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is unavailable", exception);
        }
    }

    public boolean matches(String expectedHash, String operationType, Map<String, Object> operation) {
        return expectedHash != null && expectedHash.equals(hash(operationType, operation));
    }
}
