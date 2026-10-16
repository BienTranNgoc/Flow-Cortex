package com.flowcortex.media.application;

import com.flowcortex.media.api.MediaCapability;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.Collections;
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
        @NotNull Duration providerMaxRuntime,
        @NotNull Duration providerQueueTimeout,
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
        // The provider runtime shares both queues' ack deadline, so its own ceiling counts too.
        if (providerMaxRuntime != null && consumerTimeout != null) {
            Duration required = providerMaxRuntime.plus(MAX_WORKER_BACKOFF).plus(CONSUMER_TIMEOUT_MARGIN);
            if (consumerTimeout.compareTo(required) <= 0) {
                throw new IllegalArgumentException("flowcortex.media.consumer-timeout (" + consumerTimeout
                        + ") must exceed provider-max-runtime plus the worker backoff and a 60 s margin (" + required + ")");
            }
        }
    }

    /**
     * Upload limits. {@code extensions} covers audio/video (worker render); {@code productImageExtensions}
     * covers images (product-video feature). Each entry maps to one canonical MIME type, whose prefix
     * names the kind ({@code video}, {@code audio}, {@code image}) that picks the size cap.
     */
    public record Upload(
            @Positive long maxVideoBytes,
            @Positive long maxAudioBytes,
            @Positive long maxImageBytes,
            @NotEmpty List<String> extensions,
            @NotEmpty List<String> productImageExtensions,
            @NotNull Duration urlTtl,
            @NotNull @Valid ContentCheck contentCheck) {

        /** Insertion order is the order formats are listed to the member. */
        private static final Map<String, String> CANONICAL_MIME_TYPES = canonicalMimeTypes();

        private static Map<String, String> canonicalMimeTypes() {
            Map<String, String> types = new LinkedHashMap<>();
            types.put("mp4", "video/mp4");
            types.put("mov", "video/quicktime");
            types.put("webm", "video/webm");
            types.put("mkv", "video/x-matroska");
            types.put("mp3", "audio/mpeg");
            types.put("wav", "audio/wav");
            types.put("m4a", "audio/mp4");
            types.put("jpg", "image/jpeg");
            types.put("jpeg", "image/jpeg");
            types.put("png", "image/png");
            types.put("webp", "image/webp");
            return Collections.unmodifiableMap(types);
        }

        public Upload {
            extensions = normalize(extensions, "flowcortex.media.upload.extensions");
            productImageExtensions = normalize(productImageExtensions,
                    "flowcortex.media.upload.product-image-extensions");
        }

        private static List<String> normalize(List<String> extensions, String source) {
            List<String> normalized = extensions.stream()
                    .map(e -> e.trim().toLowerCase(Locale.ROOT))
                    .toList();
            for (String extension : normalized) {
                if (!CANONICAL_MIME_TYPES.containsKey(extension)) {
                    throw new IllegalArgumentException(source + ": '" + extension
                            + "' has no canonical MIME type; allowed: "
                            + new TreeSet<>(CANONICAL_MIME_TYPES.keySet()));
                }
            }
            return normalized;
        }

        /** Configured extension → canonical MIME type, covering both lists, in configuration order. */
        public Map<String, String> mimeTypes() {
            Map<String, String> mimeTypes = new LinkedHashMap<>();
            extensions.forEach(extension -> mimeTypes.put(extension, CANONICAL_MIME_TYPES.get(extension)));
            productImageExtensions.forEach(extension -> mimeTypes.putIfAbsent(extension,
                    CANONICAL_MIME_TYPES.get(extension)));
            return mimeTypes;
        }

        /** Only the audio/video extensions, in configuration order, for the worker's allowlist. */
        public List<String> mediaExtensions() {
            return List.copyOf(extensions);
        }

        /** Only the product-image extensions, in configuration order, for the product-video allowlist. */
        public List<String> productImageExtensions() {
            return List.copyOf(productImageExtensions);
        }

        /** The kind of a canonical MIME type: its prefix before {@code /}, or {@code binary}. */
        public static String kindOf(String mimeType) {
            int separator = mimeType == null ? -1 : mimeType.indexOf('/');
            return separator > 0 ? mimeType.substring(0, separator) : "binary";
        }

        /** The size cap of {@code kind}, or 0 for a kind no upload may have. */
        public long maxBytesOf(String kind) {
            return switch (kind) {
                case "video" -> maxVideoBytes;
                case "audio" -> maxAudioBytes;
                case "image" -> maxImageBytes;
                default -> 0L;
            };
        }

        /** The configured extensions of {@code kind}, across both lists, in the canonical listing order. */
        public List<String> extensionsOf(String kind) {
            Map<String, String> configured = mimeTypes();
            return CANONICAL_MIME_TYPES.entrySet().stream()
                    .filter(entry -> configured.containsKey(entry.getKey()) && kindOf(entry.getValue()).equals(kind))
                    .map(Map.Entry::getKey)
                    .toList();
        }

        /** {@code enabled}: whether an upload's first bytes must match the kind its extension claims. */
        public record ContentCheck(@NotNull Boolean enabled) {
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
