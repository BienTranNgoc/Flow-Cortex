package com.flowcortex.workflow.application.vendor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Narrows ledger lookups to one owner's repeated ask: a SHA-256 over the capability, profile and
 * payload. The payload's {@code sourceRevisionId} is dropped because a new run mints a new one
 * for identical content.
 */
public final class VendorRequestKey {

    private static final String SOURCE_REVISION = "sourceRevisionId";
    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private VendorRequestKey() {
    }

    public static String of(String capability, String profile, Map<String, Object> payload) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("capability", capability);
        identity.put("profile", profile);
        Map<String, Object> content = new LinkedHashMap<>(payload);
        content.remove(SOURCE_REVISION);
        identity.put("payload", content);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(CANONICAL.writeValueAsString(identity).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot derive the vendor request key", e);
        }
    }
}
