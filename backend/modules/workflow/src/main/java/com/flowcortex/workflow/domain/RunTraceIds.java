package com.flowcortex.workflow.domain;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Builds the trace id a run carries from the moment it is stored. */
public final class RunTraceIds {

    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final String ALL_ZEROS = "0".repeat(32);
    private static final SecureRandom RANDOM = new SecureRandom();

    private RunTraceIds() {
    }

    /** The active trace id lowercased when it is 32 non-zero hex characters, else {@code random}'s. */
    public static String resolve(String currentTraceId, Supplier<String> random) {
        if (currentTraceId != null) {
            String id = currentTraceId.strip().toLowerCase(Locale.ROOT);
            if (TRACE_ID.matcher(id).matches() && !ALL_ZEROS.equals(id)) {
                return id;
            }
        }
        return random.get();
    }

    /** 32 lowercase hex characters, never all zeros. */
    public static String random() {
        byte[] bytes = new byte[16];
        String id;
        do {
            RANDOM.nextBytes(bytes);
            id = HexFormat.of().formatHex(bytes);
        } while (ALL_ZEROS.equals(id));
        return id;
    }
}
