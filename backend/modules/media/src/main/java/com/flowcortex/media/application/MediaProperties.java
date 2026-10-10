package com.flowcortex.media.application;

import com.flowcortex.media.api.MediaCapability;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Every media-engine timeout, limit and store setting, bound from {@code flowcortex.media.*}
 * ({@code application.yml} carries the defaults and env names). No value has a code default.
 */
@Validated
@ConfigurationProperties("flowcortex.media")
public record MediaProperties(
        @NotNull Boolean enabled,
        @NotNull Duration queueTimeout,
        @NotNull Map<String, @NotNull Duration> maxRuntime,
        @NotNull Duration consumerTimeout,
        @NotNull Duration presignTtl,
        @NotNull @Valid Upload upload,
        @NotNull @Valid S3 s3) {

    /** The worker's longest backoff before it retries a delivery. */
    static final Duration MAX_WORKER_BACKOFF = Duration.ofSeconds(60);
    static final Duration CONSUMER_TIMEOUT_MARGIN = Duration.ofSeconds(60);

    public MediaProperties {
        if (maxRuntime != null) {
            for (MediaCapability capability : MediaCapability.values()) {
                if (maxRuntime.get(capability.value()) == null) {
                    throw new IllegalArgumentException(
                            "flowcortex.media.max-runtime." + capability.value() + " is not set");
                }
            }
            // The broker cancels a delivery that is not acked within the consumer timeout, so a
            // job running to its limit, then backing off, must still finish inside it — with a margin
            // for the result publish and broker clock skew.
            Duration longestRun = maxRuntime.values().stream().max(Duration::compareTo).orElseThrow();
            Duration required = longestRun.plus(MAX_WORKER_BACKOFF).plus(CONSUMER_TIMEOUT_MARGIN);
            if (consumerTimeout != null && consumerTimeout.compareTo(required) <= 0) {
                throw new IllegalArgumentException("flowcortex.media.consumer-timeout (" + consumerTimeout
                        + ") must exceed the longest max-runtime plus the worker backoff and a 60 s margin (" + required + ")");
            }
        }
    }

    /** Upload limits; each allowed extension maps to one canonical MIME type. */
    public record Upload(@Positive long maxBytes, @NotEmpty List<String> extensions, @NotNull Duration urlTtl) {

        private static final Map<String, String> CANONICAL_MIME_TYPES = Map.ofEntries(
                Map.entry("mp4", "video/mp4"),
                Map.entry("mov", "video/quicktime"),
                Map.entry("webm", "video/webm"),
                Map.entry("mkv", "video/x-matroska"),
                Map.entry("m4a", "audio/mp4"),
                Map.entry("mp3", "audio/mpeg"),
                Map.entry("wav", "audio/wav"),
                Map.entry("jpg", "image/jpeg"),
                Map.entry("jpeg", "image/jpeg"),
                Map.entry("png", "image/png"),
                Map.entry("webp", "image/webp"));

        public Upload {
            if (extensions != null) {
                extensions = extensions.stream().map(e -> e.trim().toLowerCase(Locale.ROOT)).toList();
                for (String extension : extensions) {
                    if (!CANONICAL_MIME_TYPES.containsKey(extension)) {
                        throw new IllegalArgumentException("flowcortex.media.upload.extensions: '" + extension
                                + "' has no canonical MIME type; allowed: "
                                + new TreeSet<>(CANONICAL_MIME_TYPES.keySet()));
                    }
                }
            }
        }

        /** Configured extension → canonical MIME type, in configuration order. */
        public Map<String, String> mimeTypes() {
            Map<String, String> mimeTypes = new LinkedHashMap<>();
            extensions.forEach(extension -> mimeTypes.put(extension, CANONICAL_MIME_TYPES.get(extension)));
            return mimeTypes;
        }
    }

    /** Provider-neutral object-store settings; {@link #toString()} never prints the credential. */
    public record S3(
            String endpoint,
            String publicEndpoint,
            @NotBlank String region,
            @NotNull Boolean pathStyle,
            @NotBlank String uploadBucket,
            @NotBlank String bucket,
            String accessKey,
            String secretKey,
            @NotNull Duration apiCallTimeout,
            @NotNull Duration apiCallAttemptTimeout) {

        @Override
        public String toString() {
            return "S3[endpoint=" + endpoint + ", publicEndpoint=" + publicEndpoint + ", region=" + region
                    + ", pathStyle=" + pathStyle + ", uploadBucket=" + uploadBucket + ", bucket=" + bucket
                    + ", accessKey=" + redacted(accessKey) + ", secretKey=" + redacted(secretKey)
                    + ", apiCallTimeout=" + apiCallTimeout + ", apiCallAttemptTimeout=" + apiCallAttemptTimeout + "]";
        }

        private static String redacted(String value) {
            return value == null || value.isEmpty() ? "<unset>" : "<redacted>";
        }
    }
}
