package com.flowcortex.media.application;

import com.flowcortex.media.api.MediaCapability;
import com.flowcortex.media.api.MediaAssetInput;
import com.flowcortex.media.api.MediaDownload;
import com.flowcortex.media.api.MediaJob;
import com.flowcortex.media.api.MediaJobInput;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.MediaLimits;
import com.flowcortex.media.api.MediaOutput;
import com.flowcortex.media.api.provider.ProviderJob;
import com.flowcortex.media.api.provider.ProviderJobPayload;
import com.flowcortex.media.api.UploadGrant;
import com.flowcortex.media.api.UploadRejectedException;
import com.flowcortex.media.infrastructure.S3MediaStore;
import com.flowcortex.media.domain.MediaAsset;
import com.flowcortex.media.domain.MediaAssetBinding;
import com.flowcortex.media.infrastructure.MediaAssetBindingRepository;
import com.flowcortex.media.infrastructure.MediaAssetRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Service
class MediaJobsImpl implements MediaJobs {

    private static final Logger LOG = LoggerFactory.getLogger(MediaJobsImpl.class);
    private static final long MEGABYTE = 1024L * 1024;
    private static final long MAX_IMAGE_BYTES = 10L * MEGABYTE;
    /** The key of the one input a job built from an upload carries. */
    private static final String UPLOAD_INPUT_KEY = "media";
    private static final Pattern PLAIN_FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,254}");
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final Set<String> PROVIDER_PARAMS_KEYS = Set.of("profile", "payload");

    private final ApplicationEventPublisher events;
    private final Validator validator;
    private final MediaProperties properties;
    private final S3MediaStore store;
    private final MediaAssetRepository assets;
    private final MediaAssetBindingRepository bindings;

    MediaJobsImpl(ApplicationEventPublisher events, Validator validator, MediaProperties properties,
            S3MediaStore store, MediaAssetRepository assets, MediaAssetBindingRepository bindings) {
        this.events = events;
        this.validator = validator;
        this.properties = properties;
        this.store = store;
        this.assets = assets;
        this.bindings = bindings;
    }

    // MANDATORY: outside a transaction the after-commit externalization never fires and the job
    // would be lost silently. noRollbackFor: an invalid job must not doom the caller's
    // transaction, so the caller can still record the failure in it.
    @Override
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = ConstraintViolationException.class)
    public void enqueue(MediaJob job) {
        Set<ConstraintViolation<MediaJob>> violations = validator.validate(job);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        events.publishEvent(new MediaJobRequested(job));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = ConstraintViolationException.class)
    public void enqueueProvider(ProviderJob job) {
        Set<ConstraintViolation<ProviderJob>> violations = validator.validate(job);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        validateProviderParams(job);
        events.publishEvent(new ProviderJobRequested(job));
    }

    /** {@code params} is a map no annotation can bound, so it is checked against the
     * {@code productAdParams}/{@code speechParams} definitions the provider job schema names. */
    private void validateProviderParams(ProviderJob job) {
        ProviderParams contract = switch (job.capability()) {
            case "generate_script" -> new ProviderParams("product_ad_v1", ProviderJobPayload.ProductAd.class);
            case "generate_storyboard" -> new ProviderParams("product_ad_storyboard_v1",
                    ProviderJobPayload.Storyboard.class);
            case "generate_speech" -> new ProviderParams("vi_voice_v1", ProviderJobPayload.Speech.class);
            case "generate_video" -> new ProviderParams("vertical_ad_clip_v1", ProviderJobPayload.Video.class);
            default -> null;
        };
        if (contract == null) {
            return;
        }
        Set<String> unknown = new TreeSet<>(job.params().keySet());
        unknown.removeAll(PROVIDER_PARAMS_KEYS);
        if (!unknown.isEmpty()) {
            throw new ConstraintViolationException("params has keys the " + job.capability()
                    + " contract does not define: " + unknown, Set.of());
        }
        if (!contract.profile().equals(job.params().get("profile"))) {
            throw new ConstraintViolationException("params.profile must be " + contract.profile() + " for "
                    + job.capability(), Set.of());
        }
        Object payload;
        try {
            payload = MAPPER.convertValue(job.params().get("payload"), contract.payload());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("params.payload does not match the "
                    + job.capability() + " contract", e);
        }
        Set<? extends ConstraintViolation<?>> violations = validator.validate(payload);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }

    private record ProviderParams(String profile, Class<?> payload) {
    }

    @Override
    public boolean isEnabled() {
        return properties.enabled();
    }

    @Override
    public MediaLimits limitsFor(String capability) {
        MediaCapability known = MediaCapability.fromValue(capability);
        return new MediaLimits(properties.queueTimeout(), properties.maxRuntime().get(known.value()),
                properties.upload().maxBytes());
    }

    @Override
    public MediaLimits providerLimits(String capability) {
        if ("generate_video".equals(capability)) {
            return new MediaLimits(properties.providerVideoQueueTimeout(), properties.providerVideoMaxRuntime(),
                    properties.upload().maxBytes());
        }
        return new MediaLimits(properties.providerQueueTimeout(), properties.providerMaxRuntime(),
                properties.upload().maxBytes());
    }

    @Override
    public UploadGrant prepareUpload(UUID ownerId, String fileName, long size) {
        String contentType = allowedType(fileName)
                .orElseThrow(() -> new UploadRejectedException("fileName", typeSentence()));
        if (!withinLimit(size)) {
            throw new UploadRejectedException("size", sizeSentence());
        }
        UUID uploadId = UUID.randomUUID();
        String bucket = properties.s3().uploadBucket();
        String key = objectKey(ownerId, uploadId);
        S3MediaStore.PresignedPut put = store.presignPut(bucket, key, contentType, size, properties.upload().urlTtl());
        LOG.info("Upload grant issued: bucket={} key={} size={} expiresAt={}", bucket, key, size, put.expiresAt());
        return new UploadGrant(uploadId, put.url(), "PUT", put.headers(), put.expiresAt(),
                properties.upload().maxBytes());
    }

    @Override
    @Transactional
    public MediaJobInput resolveUpload(UUID ownerId, UUID uploadId) {
        return resolveAndRegister(ownerId, uploadId, UPLOAD_INPUT_KEY, properties.upload().mediaExtensions(), null,
                properties.upload().maxBytes()).input();
    }

    @Override
    @Transactional
    public List<MediaAssetInput> resolveImageUploads(UUID ownerId, List<UUID> uploadIds) {
        if (uploadIds == null || uploadIds.isEmpty() || uploadIds.size() > 10
                || uploadIds.stream().distinct().count() != uploadIds.size()) {
            throw new UploadRejectedException("uploadIds", "Choose between 1 and 10 different images");
        }
        List<MediaAssetInput> resolved = new java.util.ArrayList<>();
        List<String> anyAllowed = List.copyOf(properties.upload().mimeTypes().keySet());
        for (int index = 0; index < uploadIds.size(); index++) {
            resolved.add(resolveAndRegister(ownerId, uploadIds.get(index), "image_" + (index + 1), anyAllowed,
                    "image/", MAX_IMAGE_BYTES));
        }
        return List.copyOf(resolved);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<MediaAssetInput> bindRunInputs(UUID runId, List<MediaAssetInput> inputs) {
        if (inputs == null || inputs.isEmpty() || inputs.size() > 10) {
            throw new IllegalArgumentException("A run must bind between 1 and 10 assets");
        }
        Instant now = Instant.now();
        List<MediaAssetBinding> rows = new java.util.ArrayList<>();
        List<MediaAssetInput> bound = new java.util.ArrayList<>();
        for (int index = 0; index < inputs.size(); index++) {
            MediaAssetInput input = inputs.get(index);
            MediaJobInput source = input.input();
            String targetKey = "runs/" + runId + "/inputs/" + source.key();
            store.copy(source.bucket(), source.objectKey(), properties.s3().bucket(), targetKey);
            rows.add(new MediaAssetBinding(runId, source.key(), input.assetId(), (short) index, now));
            bound.add(new MediaAssetInput(input.assetId(),
                    new MediaJobInput(source.key(), properties.s3().bucket(), targetKey)));
        }
        bindings.saveAll(rows);
        return List.copyOf(bound);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void registerOutputs(UUID ownerId, UUID runId, UUID runStepId, int stepOrder, short attempt,
            List<MediaOutput> outputs) {
        if (outputs == null || outputs.isEmpty()) {
            return;
        }
        String expectedPrefix = MediaJob.outputPrefix(runId, stepOrder, attempt);
        // A job dispatched before the attempt-scoped prefix landed still writes under
        // runs/<run>/steps/<n>/. Only attempt 1 ever used that shape, so accepting it there cannot let
        // one attempt overwrite another's outputs.
        String legacyPrefix = attempt == 1 ? MediaJob.outputPrefix(runId, stepOrder) : null;
        Instant now = Instant.now();
        List<MediaAsset> registered = outputs.stream().map(output -> {
            if (!properties.s3().bucket().equals(output.bucket())
                    || !(MediaJob.isBelow(expectedPrefix, output.objectKey())
                            || legacyPrefix != null && MediaJob.isBelow(legacyPrefix, output.objectKey()))) {
                throw new IllegalArgumentException("Worker output is outside the current run prefix");
            }
            return new MediaAsset(UUID.randomUUID(), ownerId, mediaKind(output.mime()), output.bucket(),
                    output.objectKey(), output.mime(), output.bytes(), null, runStepId, attempt, now);
        }).toList();
        assets.saveAll(registered);
    }

    private MediaAssetInput resolveAndRegister(UUID ownerId, UUID uploadId, String inputKey,
            List<String> allowedExtensions, String requiredMimePrefix, long maxBytes) {
        Optional<MediaAsset> existing = assets.findById(uploadId);
        if (existing.isPresent() && !existing.orElseThrow().getOwnerId().equals(ownerId)) {
            throw refused(properties.s3().uploadBucket(), objectKey(ownerId, uploadId), "another owner",
                    "Upload the file again");
        }
        String bucket = properties.s3().uploadBucket();
        String key = objectKey(ownerId, uploadId);
        S3MediaStore.StoredObject stored = store.head(bucket, key)
                .orElseThrow(() -> refused(bucket, key, "no object", "Upload the file again"));
        if (!withinLimit(stored.size())) {
            throw refused(bucket, key, stored.size() + " bytes", sizeSentence());
        }
        Map<String, String> mimeTypes = properties.upload().mimeTypes();
        if (allowedExtensions.stream().map(mimeTypes::get).noneMatch(type -> type.equals(stored.contentType()))) {
            throw refused(bucket, key, "type " + stored.contentType(), typeSentence(allowedExtensions));
        }
        if (requiredMimePrefix != null && !stored.contentType().startsWith(requiredMimePrefix)) {
            throw new UploadRejectedException("uploadIds", "Every product asset must be an image");
        }
        if (stored.size() > maxBytes) {
            throw new UploadRejectedException("uploadIds", "Each product image must be no larger than 10 MB");
        }
        MediaAsset asset = existing.orElseGet(() -> new MediaAsset(uploadId, ownerId, mediaKind(stored.contentType()), bucket, key,
                stored.contentType(), stored.size(), stored.checksum(), Instant.now()));
        asset.refresh(stored.contentType(), stored.size(), stored.checksum());
        assets.save(asset);
        return new MediaAssetInput(asset.getId(), new MediaJobInput(inputKey, bucket, key));
    }

    private static String mediaKind(String mimeType) {
        int separator = mimeType.indexOf('/');
        return separator > 0 ? mimeType.substring(0, separator) : "binary";
    }

    @Override
    public MediaDownload presignedGet(String bucket, String key, String fileName) {
        if (!properties.s3().bucket().equals(bucket)) {
            throw new IllegalArgumentException("Only objects in the media bucket are downloadable, not " + bucket);
        }
        // The name goes into Content-Disposition as a quoted-string; anything else would need RFC 6266 encoding.
        if (!PLAIN_FILE_NAME.matcher(fileName).matches()) {
            throw new IllegalArgumentException("Not a plain file name: " + fileName.replaceAll("\\p{Cntrl}", "?"));
        }
        S3MediaStore.PresignedGet get = store.presignGet(bucket, key, "attachment; filename=\"" + fileName + "\"",
                properties.presignTtl());
        LOG.info("Download URL issued: bucket={} key={} expiresAt={}", bucket, key, get.expiresAt());
        return new MediaDownload(get.url(), get.expiresAt());
    }

    private static UploadRejectedException refused(String bucket, String key, String reason, String sentence) {
        LOG.info("Upload refused at run start: bucket={} key={} reason={}", bucket, key, reason);
        return new UploadRejectedException("uploadId", sentence);
    }

    private static String objectKey(UUID ownerId, UUID uploadId) {
        return ownerId + "/" + uploadId;
    }

    private Optional<String> allowedType(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return Optional.empty();
        }
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return Optional.ofNullable(properties.upload().mimeTypes().get(extension));
    }

    private boolean withinLimit(long size) {
        return size >= 1 && size <= properties.upload().maxBytes();
    }

    private String sizeSentence() {
        long max = properties.upload().maxBytes();
        String limit = max % MEGABYTE == 0 ? max / MEGABYTE + " MB" : max + " bytes";
        return "Files must be between 1 byte and " + limit + ".";
    }

    private String typeSentence() {
        List<String> extensions = new java.util.ArrayList<>(properties.upload().mediaExtensions());
        extensions.addAll(properties.upload().productImageExtensions());
        return typeSentence(extensions);
    }

    private static String typeSentence(List<String> allowedExtensions) {
        List<String> extensions = allowedExtensions.stream().map(e -> "." + e).toList();
        String allowed = extensions.size() == 1 ? extensions.getFirst()
                : String.join(", ", extensions.subList(0, extensions.size() - 1)) + " or " + extensions.getLast();
        return "This file type can't be checked yet. Use " + allowed + ".";
    }
}
