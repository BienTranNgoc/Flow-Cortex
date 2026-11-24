package com.flowcortex.user.apicall;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Bound from {@code flowcortex.auth.api-token-calls.*} ({@code application.yml} carries the defaults
 * and env names). A daily limit of 0 switches that cap off.
 */
@Validated
@ConfigurationProperties("flowcortex.auth.api-token-calls")
record ApiTokenCallsProperties(int dailyWriteLimit, int dailyWorkflowCreateLimit, @NotNull Duration retention,
        @NotNull Duration idempotencyWindow, @NotNull Duration idempotencyStaleAfter,
        @NotNull DataSize maxKeyedRequestBody, @NotNull DataSize maxStoredResponseBody) {

    private static final String PREFIX = "flowcortex.auth.api-token-calls.";

    ApiTokenCallsProperties {
        if (dailyWriteLimit < 0) {
            throw new IllegalStateException(PREFIX + "daily-write-limit must not be negative, was " + dailyWriteLimit);
        }
        if (dailyWorkflowCreateLimit < 0) {
            throw new IllegalStateException(
                    PREFIX + "daily-workflow-create-limit must not be negative, was " + dailyWorkflowCreateLimit);
        }
        requirePositive("retention", retention);
        requirePositive("idempotency-window", idempotencyWindow);
        requirePositive("idempotency-stale-after", idempotencyStaleAfter);
        requirePositive("max-keyed-request-body", maxKeyedRequestBody);
        requirePositive("max-stored-response-body", maxStoredResponseBody);
    }

    private static void requirePositive(String name, Duration value) {
        if (value != null && (value.isZero() || value.isNegative())) {
            throw new IllegalStateException(PREFIX + name + " must be positive, was " + value);
        }
    }

    private static void requirePositive(String name, DataSize value) {
        if (value != null && value.toBytes() <= 0) {
            throw new IllegalStateException(PREFIX + name + " must be positive, was " + value);
        }
    }
}
