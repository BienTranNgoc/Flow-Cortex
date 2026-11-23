package com.flowcortex.onboarding.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** SHA-256 hex over the workflow id, source locale and sample input. */
public final class SampleSourceHash {

    private SampleSourceHash() {
    }

    public static String of(UUID workflowId, String sourceLocale, String sampleInput) {
        String joined = workflowId + "\n" + sourceLocale + "\n" + sampleInput;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }
}
