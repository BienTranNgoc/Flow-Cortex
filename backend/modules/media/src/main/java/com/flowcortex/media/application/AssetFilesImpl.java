package com.flowcortex.media.application;

import com.flowcortex.media.api.AssetFileLink;
import com.flowcortex.media.api.AssetFiles;
import com.flowcortex.media.api.MediaStoreUnavailableException;
import com.flowcortex.media.infrastructure.S3MediaStore;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Optional;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;

@Service
class AssetFilesImpl implements AssetFiles {

    private static final Logger LOG = LoggerFactory.getLogger(AssetFilesImpl.class);
    /** RFC 5987 attr-char beyond letters and digits: everything else in {@code filename*} is percent-encoded. */
    private static final String ATTR_CHARS = "!#$&+-.^_`|~";

    private final S3MediaStore store;
    private final MediaProperties properties;

    AssetFilesImpl(S3MediaStore store, MediaProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @Override
    public OptionalLong probe(String bucket, String key) {
        requireMediaBucket(bucket);
        try {
            Optional<S3MediaStore.StoredObject> object = store.head(bucket, key);
            return object.isPresent() ? OptionalLong.of(object.get().size()) : OptionalLong.empty();
        } catch (SdkException e) {
            LOG.warn("Asset file probe failed: bucket={} key={}", bucket, key, e);
            throw new MediaStoreUnavailableException("The object store did not answer for the asset file", e);
        }
    }

    @Override
    public AssetFileLink sign(String bucket, String key, Disposition disposition, String fileName,
            String mimeType) {
        requireMediaBucket(bucket);
        String name = fileName == null ? "" : stripControl(fileName);
        if (name.isBlank()) {
            throw new IllegalArgumentException("An asset file link needs a file name");
        }
        String header = disposition.token() + "; filename=\"" + asciiFallback(name) + "\"; filename*=UTF-8''"
                + percentEncode(name);
        S3MediaStore.PresignedGet get = store.presignGet(bucket, key, header, properties.presignTtl());
        LOG.info("Asset file link issued: bucket={} key={} disposition={} mime={} expiresAt={}", bucket, key,
                disposition.token(), mimeType, get.expiresAt());
        return new AssetFileLink(get.url(), get.expiresAt());
    }

    private void requireMediaBucket(String bucket) {
        if (!properties.s3().bucket().equals(bucket)) {
            throw new IllegalArgumentException("Only objects in the media bucket are asset files, not " + bucket);
        }
    }

    private static String stripControl(String value) {
        return value.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    /** Accents folded away, {@code đ} to {@code d}, any other non-ASCII character to {@code _}; no quote, backslash or slash. */
    private static String asciiFallback(String name) {
        String folded = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('đ', 'd')
                .replace('Đ', 'D');
        StringBuilder out = new StringBuilder();
        folded.codePoints().forEach(codePoint -> {
            if (codePoint == '"' || codePoint == '\\' || codePoint == '/') {
                return;
            }
            out.append(codePoint >= 0x20 && codePoint < 0x7F ? (char) codePoint : '_');
        });
        return out.toString();
    }

    private static String percentEncode(String name) {
        StringBuilder out = new StringBuilder();
        for (byte b : name.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            boolean plain = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || ATTR_CHARS.indexOf(c) >= 0;
            if (plain) {
                out.append(c);
            } else {
                out.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        return out.toString();
    }
}
