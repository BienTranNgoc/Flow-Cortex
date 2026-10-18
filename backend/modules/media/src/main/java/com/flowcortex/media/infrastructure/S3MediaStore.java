package com.flowcortex.media.infrastructure;

import com.flowcortex.media.api.UploadRejectedException;
import com.flowcortex.media.application.MediaProperties;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.TaggingDirective;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

/**
 * The object store, through two clients: one on the internal endpoint for the server's own
 * calls, one that signs URLs for the public endpoint the member's browser reaches. A URL signed
 * for the internal host would not resolve outside the container network.
 */
@Component
public class S3MediaStore implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(S3MediaStore.class);
    /** Signed, but set by the browser itself; a script may not set them. */
    private static final Set<String> SET_BY_THE_BROWSER = Set.of("host", "content-length");
    static final String RUN_INPUT_TAG = "flowcortex-retention=run-input";

    private final S3Client client;
    private final S3Presigner presigner;

    public S3MediaStore(MediaProperties properties) {
        MediaProperties.S3 s3 = properties.s3();
        Region region = Region.of(s3.region());
        AwsCredentialsProvider credentials = credentials(s3);
        S3Configuration addressing = S3Configuration.builder().pathStyleAccessEnabled(s3.pathStyle()).build();

        S3ClientBuilder clientBuilder = S3Client.builder()
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(addressing)
                .overrideConfiguration(timeouts -> timeouts
                        .apiCallTimeout(s3.apiCallTimeout())
                        .apiCallAttemptTimeout(s3.apiCallAttemptTimeout()));
        endpoint(s3.endpoint()).ifPresent(clientBuilder::endpointOverride);
        this.client = clientBuilder.build();

        S3Presigner.Builder presignerBuilder = S3Presigner.builder()
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(addressing);
        endpoint(s3.publicEndpoint()).ifPresent(presignerBuilder::endpointOverride);
        this.presigner = presignerBuilder.build();
    }

    /** A signed PUT of exactly {@code contentLength} bytes of {@code contentType}; {@code headers} are what the caller must send. */
    public PresignedPut presignPut(String bucket, String key, String contentType, long contentLength, Duration ttl) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .contentLength(contentLength)
                .build();
        PresignedPutObjectRequest signed = presigner.presignPutObject(request -> request
                .signatureDuration(ttl)
                .putObjectRequest(put));
        Map<String, String> headers = new LinkedHashMap<>();
        signed.signedHeaders().forEach((name, values) -> {
            if (!SET_BY_THE_BROWSER.contains(name.toLowerCase(Locale.ROOT))) {
                headers.put(canonicalName(name), String.join(",", values));
            }
        });
        return new PresignedPut(signed.url().toString(), headers, signed.expiration());
    }

    /** A signed GET of {@code key} whose response carries {@code contentDisposition}. */
    public PresignedGet presignGet(String bucket, String key, String contentDisposition, Duration ttl) {
        PresignedGetObjectRequest signed = presigner.presignGetObject(request -> request
                .signatureDuration(ttl)
                .getObjectRequest(get -> get.bucket(bucket).key(key).responseContentDisposition(contentDisposition)));
        return new PresignedGet(signed.url().toString(), signed.expiration());
    }

    /** @throws software.amazon.awssdk.core.exception.SdkException if the bucket cannot be reached or does not exist */
    public void headBucket(String bucket) {
        client.headBucket(request -> request.bucket(bucket));
    }

    /** The object's size and type, or empty if there is no object at {@code key}. */
    public Optional<StoredObject> head(String bucket, String key) {
        try {
            HeadObjectResponse head = client.headObject(request -> request.bucket(bucket).key(key));
            return Optional.of(new StoredObject(head.contentLength(), head.contentType(), head.checksumSHA256()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    /** At most the first {@code length} bytes of the object (a ranged GET), or empty if there is no object at {@code key}. */
    public Optional<byte[]> readPrefix(String bucket, String key, int length) {
        try {
            return Optional.of(client.getObjectAsBytes(request -> request
                    .bucket(bucket).key(key).range("bytes=0-" + (length - 1))).asByteArray());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    /**
     * A server-side copy of an upload into a run's inputs; nothing streams through this process. The
     * copy carries only {@value #RUN_INPUT_TAG}, which the media bucket's lifecycle rule expires by.
     * @throws UploadRejectedException on field {@code uploadIds} if the upload is gone
     */
    public void copy(String sourceBucket, String sourceKey, String targetBucket, String targetKey) {
        try {
            client.copyObject(request -> request
                    .sourceBucket(sourceBucket).sourceKey(sourceKey)
                    .destinationBucket(targetBucket).destinationKey(targetKey)
                    .tagging(RUN_INPUT_TAG)
                    .taggingDirective(TaggingDirective.REPLACE));
        } catch (NoSuchKeyException e) {
            LOG.info("Upload refused at run start: bucket={} key={} reason=gone before copy", sourceBucket, sourceKey);
            throw new UploadRejectedException("uploadIds", "Upload the file again");
        }
    }

    @Override
    public void close() {
        presigner.close();
        client.close();
    }

    private static AwsCredentialsProvider credentials(MediaProperties.S3 s3) {
        if (s3.accessKey() == null || s3.accessKey().isBlank()) {
            return DefaultCredentialsProvider.builder().build();
        }
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(s3.accessKey(), s3.secretKey()));
    }

    private static Optional<URI> endpoint(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(URI.create(value));
    }

    private static String canonicalName(String header) {
        return Arrays.stream(header.split("-"))
                .map(part -> part.isEmpty() ? part : Character.toUpperCase(part.charAt(0)) + part.substring(1))
                .collect(Collectors.joining("-"));
    }

    /** A presigned upload: the URL, the headers the PUT must carry, and when the URL stops working. */
    public record PresignedPut(String url, Map<String, String> headers, Instant expiresAt) {

        /** Without the URL: it grants the upload to whoever holds it until it expires. */
        @Override
        public String toString() {
            return "PresignedPut[url=<redacted>, headers=" + headers + ", expiresAt=" + expiresAt + "]";
        }
    }

    /** A presigned download and when it stops working. */
    public record PresignedGet(String url, Instant expiresAt) {

        /** Without the URL: it grants the download to whoever holds it until it expires. */
        @Override
        public String toString() {
            return "PresignedGet[url=<redacted>, expiresAt=" + expiresAt + "]";
        }
    }

    public record StoredObject(long size, String contentType, String checksum) {

        public StoredObject(long size, String contentType) {
            this(size, contentType, null);
        }
    }
}
