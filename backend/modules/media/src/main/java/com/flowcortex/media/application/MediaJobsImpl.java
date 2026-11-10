package com.flowcortex.media.application;

import com.flowcortex.media.api.MediaCapability;
import com.flowcortex.media.api.MediaAssetInput;
import com.flowcortex.media.api.MediaDownload;
import com.flowcortex.media.api.MediaJob;
import com.flowcortex.media.api.MediaJobInput;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.MediaLimits;
import com.flowcortex.media.api.MediaOutput;
import com.flowcortex.media.api.MediaStoreUnavailableException;
import com.flowcortex.media.api.RunInput;
import com.flowcortex.media.api.StoredAttachment;
import com.flowcortex.media.api.provider.ProviderJob;
import com.flowcortex.media.api.provider.ProviderJobPayload;
import com.flowcortex.media.api.UploadGrant;
import com.flowcortex.media.api.UploadPurpose;
import com.flowcortex.media.api.UploadRejectedException;
import com.flowcortex.media.infrastructure.S3MediaStore;
import com.flowcortex.media.domain.MediaAsset;
import com.flowcortex.media.domain.MediaAssetBinding;
import com.flowcortex.media.infrastructure.MediaAssetBindingRepository;
import com.flowcortex.media.infrastructure.MediaAssetRepository;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.space.api.PersonalSpaces;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import software.amazon.awssdk.core.exception.SdkException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Service
class MediaJobsImpl implements MediaJobs {

    private static final Logger LOG = LoggerFactory.getLogger(MediaJobsImpl.class);
    /** The key of the one input a job built from an upload carries. */
    private static final String UPLOAD_INPUT_KEY = "media";
    private static final Pattern PLAIN_FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,254}");
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final Set<String> PROVIDER_PARAMS_KEYS = Set.of("profile", "payload");
    /** Retention tag values the media bucket's lifecycle rules key on: pending expires, kept stays. */
    private record RetentionTags(String pending, String kept) {
    }

    /** The key roots attachments may be kept under, each with its own retention tags. */
    private static final Map<String, RetentionTags> ATTACHMENT_ROOTS = Map.of(
            "support/", new RetentionTags("support-pending", "support-attachment"),
            "workflow-review/", new RetentionTags("review-pending", "review-image"));

    private static Optional<RetentionTags> rootTagsOf(String key) {
        return ATTACHMENT_ROOTS.entrySet().stream()
                .filter(root -> key.startsWith(root.getKey()))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    private final ApplicationEventPublisher events;
    private final Validator validator;
    private final MediaProperties properties;
    private final S3MediaStore store;
    private final MediaAssetRepository assets;
    private final MediaAssetBindingRepository bindings;
    private final PersonalSpaces personalSpaces;
    private final UploadPurposePolicy purposes;

    MediaJobsImpl(ApplicationEventPublisher events, Validator validator, MediaProperties properties,
            S3MediaStore store, MediaAssetRepository assets, MediaAssetBindingRepository bindings,
            PersonalSpaces personalSpaces) {
        this.events = events;
        this.validator = validator;
        this.properties = properties;
        this.store = store;
        this.assets = assets;
        this.bindings = bindings;
        this.personalSpaces = personalSpaces;
        this.purposes = new UploadPurposePolicy(properties.upload());
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
                largestMediaInputBytes());
    }

    @Override
    public MediaLimits providerLimits(String capability) {
        if ("generate_video".equals(capability)) {
            return new MediaLimits(properties.providerVideoQueueTimeout(), properties.providerVideoMaxRuntime(),
                    largestMediaInputBytes());
        }
        return new MediaLimits(properties.providerQueueTimeout(), properties.providerMaxRuntime(),
                largestMediaInputBytes());
    }

    /** A capability's input is an audio or video upload, so its bound is the larger of those two caps. */
    private long largestMediaInputBytes() {
        return Math.max(properties.upload().maxVideoBytes(), properties.upload().maxAudioBytes());
    }

    @Override
    public UploadGrant prepareUpload(UploadPurpose purpose, UUID ownerId, String fileName, long size) {
        UploadPurposePolicy.Allowed allowed = purposes.grant(purpose, fileName, size);
        UUID uploadId = UUID.randomUUID();
        String bucket = properties.s3().uploadBucket();
        String key = purposes.objectKey(purpose, ownerId, uploadId);
        S3MediaStore.PresignedPut put = store.presignPut(bucket, key, allowed.contentType(), size,
                properties.upload().urlTtl());
        LOG.info("Upload grant issued: purpose={} bucket={} key={} size={} expiresAt={}", purpose, bucket, key, size,
                put.expiresAt());
        return new UploadGrant(uploadId, put.url(), "PUT", put.headers(), put.expiresAt(), allowed.maxBytes());
    }

    @Override
    @Transactional
    public MediaAssetInput resolveUpload(UUID ownerId, UUID uploadId) {
        return resolveAndRegister(UploadPurpose.RUN_INPUT, ownerId, uploadId, UPLOAD_INPUT_KEY,
                properties.upload().mediaExtensions(), null);
    }

    @Override
    @Transactional
    public List<MediaAssetInput> resolveImageUploads(UploadPurpose purpose, UUID ownerId, List<UUID> uploadIds) {
        if (uploadIds == null || uploadIds.isEmpty() || uploadIds.size() > 10
                || uploadIds.stream().distinct().count() != uploadIds.size()) {
            throw new UploadRejectedException("uploadIds", new LocalizedMessage("upload.imageCount"));
        }
        List<MediaAssetInput> resolved = new java.util.ArrayList<>();
        List<String> anyAllowed = List.copyOf(purposes.mimeTypes(purpose).keySet());
        for (int index = 0; index < uploadIds.size(); index++) {
            resolved.add(resolveAndRegister(purpose, ownerId, uploadIds.get(index), "image_" + (index + 1),
                    anyAllowed, "image/"));
        }
        return List.copyOf(resolved);
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public List<MediaAssetInput> copyRunInputs(UUID runId, List<MediaAssetInput> inputs) {
        requireBindable(inputs);
        List<MediaAssetInput> copies = new java.util.ArrayList<>();
        for (MediaAssetInput input : inputs) {
            MediaJobInput source = input.input();
            String targetKey = runInputKey(runId, source.key());
            MediaAsset asset = assets.findById(input.assetId())
                    .orElseThrow(() -> new IllegalStateException("Bound asset is not registered: " + input.assetId()));
            if ("image".equals(asset.getKind())) {
                String bucket = properties.s3().bucket();
                HardenedImage image = hardenedImage(source, asset.getMimeType(),
                        purposes.maxBytes(UploadPurpose.RUN_INPUT, "image"), reason -> {
                            LOG.info("Upload refused at run start: bucket={} key={} reason={}", source.bucket(),
                                    source.objectKey(), reason);
                            return new UploadRejectedException("uploadIds", invalidContentDetail("image"));
                        });
                store.put(bucket, targetKey, image.contentType(), image.bytes(), S3MediaStore.RUN_INPUT_RETENTION);
            } else {
                try {
                    store.copy(source.bucket(), source.objectKey(), properties.s3().bucket(), targetKey);
                } catch (UploadRejectedException e) {
                    if (UPLOAD_INPUT_KEY.equals(source.key())) {
                        throw new UploadRejectedException("uploadId", e.detail());
                    }
                    throw e;
                }
            }
            if (properties.upload().contentCheck().enabled()) {
                checkContent(input.assetId(), properties.s3().bucket(), targetKey);
            }
            copies.add(new MediaAssetInput(input.assetId(),
                    new MediaJobInput(source.key(), properties.s3().bucket(), targetKey)));
        }
        return List.copyOf(copies);
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public List<StoredAttachment> keepAttachments(String keyPrefix, List<MediaAssetInput> inputs, long maxBytes) {
        RetentionTags tags = keyPrefix == null || keyPrefix.endsWith("/") ? null : rootTagsOf(keyPrefix).orElse(null);
        if (tags == null) {
            throw new IllegalArgumentException("Attachments are kept only under " + ATTACHMENT_ROOTS.keySet() + ": "
                    + keyPrefix);
        }
        if (inputs == null || inputs.isEmpty() || inputs.size() > 10) {
            throw new IllegalArgumentException("Keep between 1 and 10 attachments");
        }
        List<MediaAsset> sources = new java.util.ArrayList<>();
        for (MediaAssetInput input : inputs) {
            MediaAsset asset = assets.findById(input.assetId())
                    .orElseThrow(() -> new IllegalStateException("Attachment is not registered: " + input.assetId()));
            if (!"image".equals(asset.getKind()) || asset.getBytes() > maxBytes) {
                LOG.info("Attachment refused: asset={} kind={} bytes={} max={}", asset.getId(), asset.getKind(),
                        asset.getBytes(), maxBytes);
                throw new UploadRejectedException("uploadIds",
                        new LocalizedMessage("upload.attachmentSize", maxBytes));
            }
            sources.add(asset);
        }
        String bucket = properties.s3().bucket();
        List<StoredAttachment> kept = new java.util.ArrayList<>();
        for (int index = 0; index < inputs.size(); index++) {
            MediaJobInput source = inputs.get(index).input();
            String targetKey = keyPrefix + "/" + (index + 1);
            HardenedImage image = hardenedImage(source, sources.get(index).getMimeType(), maxBytes,
                    reason -> refusedAttachment(source.bucket(), source.objectKey(), reason));
            store.put(bucket, targetKey, image.contentType(), image.bytes(), tags.pending());
            if (properties.upload().contentCheck().enabled()) {
                byte[] prefix = store.readPrefix(bucket, targetKey, ContentSniffer.PREFIX_LENGTH)
                        .orElseThrow(() -> refusedAttachment(bucket, targetKey, "gone before the content check"));
                if (!ContentSniffer.matches("image", prefix)) {
                    throw refusedAttachment(bucket, targetKey, "content is not image");
                }
                if (!sniffedImageType(prefix).equals(sources.get(index).getMimeType())) {
                    throw refusedAttachment(bucket, targetKey, "content is not " + sources.get(index).getMimeType());
                }
            }
            MediaAsset asset = sources.get(index);
            kept.add(new StoredAttachment(bucket, targetKey, asset.getMimeType(), image.bytes().length));
        }
        List<StoredAttachment> retagged = new java.util.ArrayList<>();
        try {
            for (StoredAttachment attachment : kept) {
                store.retag(bucket, attachment.objectKey(), tags.kept());
                retagged.add(attachment);
            }
        } catch (RuntimeException e) {
            try {
                releaseAttachments(retagged);
            } catch (RuntimeException release) {
                e.addSuppressed(release);
            }
            throw e;
        }
        return List.copyOf(kept);
    }

    @Override
    public void releaseAttachments(List<StoredAttachment> attachments) {
        String bucket = properties.s3().bucket();
        for (StoredAttachment attachment : attachments) {
            if (!bucket.equals(attachment.bucket()) || rootTagsOf(attachment.objectKey()).isEmpty()) {
                throw new IllegalArgumentException("Not a kept attachment: " + attachment.bucket() + "/"
                        + attachment.objectKey());
            }
        }
        RuntimeException first = null;
        for (StoredAttachment attachment : attachments) {
            try {
                store.retag(bucket, attachment.objectKey(), rootTagsOf(attachment.objectKey()).orElseThrow().pending());
                LOG.info("Attachment released: bucket={} key={}", bucket, attachment.objectKey());
            } catch (RuntimeException e) {
                LOG.warn("Attachment release failed: bucket={} key={} error={}", bucket, attachment.objectKey(),
                        e.getClass().getSimpleName());
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    /** An image ready to be written: its metadata stripped, typed by what its bytes are. */
    private record HardenedImage(byte[] bytes, String contentType) {
    }

    /**
     * Reads the upload and returns it stripped of metadata, whatever the content-check switch says: the
     * format comes from the bytes and must be the declared type, the header must parse and hold at most
     * {@value ImageHardening#MAX_PIXELS} pixels. Each failure but the pixel cap goes through {@code notAnImage},
     * which receives the reason to log; an object that grew past {@code maxBytes} since it was granted is one.
     */
    private HardenedImage hardenedImage(MediaJobInput source, String declaredType, long maxBytes,
            java.util.function.Function<String, UploadRejectedException> notAnImage) {
        byte[] bytes = store.read(source.bucket(), source.objectKey(), Math.toIntExact(maxBytes));
        if (bytes.length > maxBytes) {
            throw notAnImage.apply("over " + maxBytes + " bytes");
        }
        ImageHardening.ImageFormat format = ImageHardening.detect(bytes)
                .orElseThrow(() -> notAnImage.apply("content is not an image"));
        if (!format.mimeType().equals(declaredType)) {
            throw notAnImage.apply("content is " + format.mimeType() + ", not " + declaredType);
        }
        ImageHardening.Dimensions dimensions = ImageHardening.dimensions(format, bytes)
                .orElseThrow(() -> notAnImage.apply("unreadable " + format + " header"));
        if (!ImageHardening.withinPixelCap(dimensions)) {
            LOG.info("Image refused: bucket={} key={} width={} height={}", source.bucket(), source.objectKey(),
                    dimensions.width(), dimensions.height());
            throw new UploadRejectedException("uploadIds", new LocalizedMessage("upload.imageTooLarge"));
        }
        byte[] stripped = ImageHardening.strip(format, bytes)
                .orElseThrow(() -> notAnImage.apply("unreadable " + format + " body"));
        return new HardenedImage(stripped, format.mimeType());
    }

    /** The MIME type of an image prefix {@link ContentSniffer} already accepted as an image. */
    private static String sniffedImageType(byte[] prefix) {
        if (prefix[0] == (byte) 0x89) {
            return "image/png";
        }
        if (prefix[0] == (byte) 0xFF) {
            return "image/jpeg";
        }
        return "image/webp";
    }

    /** The refused copy keeps its pending tag and expires; the backend may not delete in the media bucket. */
    private static UploadRejectedException refusedAttachment(String bucket, String key, String reason) {
        LOG.info("Attachment refused: bucket={} key={} reason={}", bucket, key, reason);
        return new UploadRejectedException("uploadIds", new LocalizedMessage("upload.attachmentNotImage"));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void bindRunInputs(UUID runId, List<MediaAssetInput> copies) {
        requireBindable(copies);
        Instant now = Instant.now();
        List<MediaAssetBinding> rows = new java.util.ArrayList<>();
        for (int index = 0; index < copies.size(); index++) {
            MediaAssetInput copy = copies.get(index);
            MediaJobInput input = copy.input();
            if (!properties.s3().bucket().equals(input.bucket())
                    || !runInputKey(runId, input.key()).equals(input.objectKey())) {
                throw new IllegalArgumentException("Only a copy under the run's inputs can be bound: " + input.key());
            }
            rows.add(new MediaAssetBinding(runId, input.key(), copy.assetId(), (short) index, now));
        }
        bindings.saveAll(rows);
    }

    private static void requireBindable(List<MediaAssetInput> inputs) {
        if (inputs == null || inputs.isEmpty() || inputs.size() > 10) {
            throw new IllegalArgumentException("A run must bind between 1 and 10 assets");
        }
    }

    private static String runInputKey(UUID runId, String bindingKey) {
        return "runs/" + runId + "/inputs/" + bindingKey;
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
        UUID spaceId = personalSpaces.personalSpaceOf(ownerId);
        List<MediaAsset> registered = outputs.stream().map(output -> {
            if (!properties.s3().bucket().equals(output.bucket())
                    || !(MediaJob.isBelow(expectedPrefix, output.objectKey())
                            || legacyPrefix != null && MediaJob.isBelow(legacyPrefix, output.objectKey()))) {
                throw new IllegalArgumentException("Worker output is outside the current run prefix");
            }
            return new MediaAsset(UUID.randomUUID(), ownerId, spaceId, MediaProperties.Upload.kindOf(output.mime()),
                    output.bucket(), output.objectKey(), output.mime(), output.bytes(), null, runStepId, attempt, now);
        }).toList();
        assets.saveAll(registered);
    }

    private MediaAssetInput resolveAndRegister(UploadPurpose purpose, UUID ownerId, UUID uploadId, String inputKey,
            List<String> allowedExtensions, String requiredMimePrefix) {
        String bucket = properties.s3().uploadBucket();
        String key = purposes.objectKey(purpose, ownerId, uploadId);
        Optional<MediaAsset> existing = assets.findById(uploadId);
        if (existing.isPresent() && !existing.orElseThrow().getOwnerId().equals(ownerId)) {
            throw refused(bucket, key, "another owner", new LocalizedMessage("upload.again"));
        }
        S3MediaStore.StoredObject stored = store.head(bucket, key)
                .orElseThrow(() -> refused(bucket, key, "no object", new LocalizedMessage("upload.again")));
        Map<String, String> mimeTypes = purposes.mimeTypes(purpose);
        if (allowedExtensions.stream().map(mimeTypes::get).noneMatch(type -> type.equals(stored.contentType()))) {
            throw refused(bucket, key, "type " + stored.contentType(), typeDetail(allowedExtensions));
        }
        if (requiredMimePrefix != null && !stored.contentType().startsWith(requiredMimePrefix)) {
            throw new UploadRejectedException("uploadIds", new LocalizedMessage("upload.notAnImage"));
        }
        String kind = MediaProperties.Upload.kindOf(stored.contentType());
        if (stored.size() < 1 || stored.size() > purposes.maxBytes(purpose, kind)) {
            throw refused(bucket, key, stored.size() + " bytes", purposes.sizeDetail(purpose, kind));
        }
        MediaAsset asset = existing.orElseGet(() -> new MediaAsset(uploadId, ownerId,
                personalSpaces.personalSpaceOf(ownerId), kind, bucket, key, stored.contentType(), stored.size(),
                stored.checksum(), Instant.now()));
        asset.refresh(stored.contentType(), stored.size(), stored.checksum());
        assets.save(asset);
        return new MediaAssetInput(asset.getId(), new MediaJobInput(inputKey, bucket, key));
    }

    /**
     * Sniffs the run's copy rather than the upload: the upload's presigned URL stays valid after the run
     * starts, while the copy is out of the member's reach. A refused copy stays under the run prefix until
     * the media bucket's run-input lifecycle rule expires it; the backend may not delete there.
     */
    private void checkContent(UUID assetId, String bucket, String key) {
        String kind = assets.findById(assetId)
                .orElseThrow(() -> new IllegalStateException("Bound asset is not registered: " + assetId))
                .getKind();
        byte[] prefix = store.readPrefix(bucket, key, ContentSniffer.PREFIX_LENGTH)
                .orElseThrow(() -> refused(bucket, key, "gone before the content check",
                        new LocalizedMessage("upload.again")));
        if (!ContentSniffer.matches(kind, prefix)) {
            throw refused(bucket, key, "content is not " + kind, invalidContentDetail(kind));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<RunInput> runInputs(UUID runId) {
        String bucket = properties.s3().bucket();
        List<RunInput> inputs = new ArrayList<>();
        for (MediaAssetBinding binding : bindings.findByRunIdOrderByPositionAsc(runId)) {
            Optional<MediaAsset> asset = assets.findById(binding.getAssetId());
            if (asset.isEmpty()) {
                LOG.warn("Run input skipped, its asset is not registered: runId={} bindingKey={} assetId={}",
                        runId, binding.getBindingKey(), binding.getAssetId());
                continue;
            }
            String bindingKey = binding.getBindingKey();
            String mimeType = asset.get().getMimeType();
            MediaDownload download = presignedGet(bucket, runInputKey(runId, bindingKey),
                    "input-" + bindingKey + "." + extensionOf(mimeType));
            inputs.add(new RunInput(bindingKey, asset.get().getKind(), mimeType, download.url(),
                    download.expiresAt()));
        }
        return inputs;
    }

    private String extensionOf(String mimeType) {
        return properties.upload().mimeTypes().entrySet().stream()
                .filter(entry -> entry.getValue().equals(mimeType))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse("bin");
    }

    @Override
    public boolean isDownloadable(String bucket) {
        return properties.s3().bucket().equals(bucket);
    }

    @Override
    public MediaDownload presignedGet(String bucket, String key, String fileName) {
        if (!isDownloadable(bucket)) {
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

    @Override
    public MediaOutput putRunObject(UUID runId, int stepOrder, String name, String mime, byte[] bytes) {
        if (!PLAIN_FILE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Not a plain file name: " + name.replaceAll("\\p{Cntrl}", "?"));
        }
        String bucket = properties.s3().uploadBucket();
        String key = MediaJob.outputPrefix(runId, stepOrder) + name;
        try {
            store.put(bucket, key, mime, bytes);
        } catch (SdkException e) {
            // The message may reach a step's error message; where the object was going stays in the log.
            LOG.warn("Run object not stored: bucket={} key={} bytes={}", bucket, key, bytes.length, e);
            throw new MediaStoreUnavailableException("The object store did not take the step's file", e);
        }
        LOG.info("Run object stored: bucket={} key={} bytes={}", bucket, key, bytes.length);
        return new MediaOutput(name, bucket, key, mime, (long) bytes.length, null);
    }

    private static UploadRejectedException refused(String bucket, String key, String reason,
            LocalizedMessage detail) {
        LOG.info("Upload refused at run start: bucket={} key={} reason={}", bucket, key, reason);
        return new UploadRejectedException("uploadId", detail);
    }

    /** Only reached after the size check, which refuses every kind but video, audio and image. */
    private LocalizedMessage invalidContentDetail(String kind) {
        return switch (kind) {
            case "video" -> new LocalizedMessage("upload.invalidVideo", formats(kind));
            case "audio" -> new LocalizedMessage("upload.invalidAudio", formats(kind));
            default -> new LocalizedMessage("upload.invalidImage", formats(kind));
        };
    }

    private String formats(String kind) {
        return String.join(", ", properties.upload().extensionsOf(kind).stream().map(e -> "." + e).toList());
    }

    private static LocalizedMessage typeDetail(List<String> allowedExtensions) {
        List<String> extensions = allowedExtensions.stream().map(e -> "." + e).toList();
        return extensions.size() == 1
                ? new LocalizedMessage("upload.typeOne", extensions.getFirst())
                : new LocalizedMessage("upload.typeMany",
                        String.join(", ", extensions.subList(0, extensions.size() - 1)), extensions.getLast());
    }
}
