package com.flowcortex.media.application;

import com.flowcortex.media.api.BoundRunInput;
import com.flowcortex.media.api.MediaStoreUnavailableException;
import com.flowcortex.media.api.RegistrableMedia;
import com.flowcortex.media.api.StoredMediaFile;
import com.flowcortex.media.domain.MediaAsset;
import com.flowcortex.media.domain.MediaAssetBinding;
import com.flowcortex.media.infrastructure.MediaAssetBindingRepository;
import com.flowcortex.media.infrastructure.MediaAssetRepository;
import com.flowcortex.media.infrastructure.S3MediaStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

@Service
class RegistrableMediaImpl implements RegistrableMedia {

    private static final Logger LOG = LoggerFactory.getLogger(RegistrableMediaImpl.class);
    private static final Pattern RUN_INPUT_KEY = Pattern.compile(
            "runs/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/inputs/[^/]+");
    /** Retention tag value the media bucket's lifecycle rules leave alone. */
    private static final String KEPT = "asset";

    private final S3MediaStore store;
    private final MediaAssetRepository assets;
    private final MediaAssetBindingRepository bindings;
    private final MediaProperties properties;

    RegistrableMediaImpl(S3MediaStore store, MediaAssetRepository assets, MediaAssetBindingRepository bindings,
            MediaProperties properties) {
        this.store = store;
        this.assets = assets;
        this.bindings = bindings;
        this.properties = properties;
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoredMediaFile> stepOutputs(UUID runStepId, short attempt) {
        String mediaBucket = properties.s3().bucket();
        return assets.findBySourceRunStepIdAndSourceAttempt(runStepId, attempt).stream()
                .sorted(Comparator.comparing(MediaAsset::getObjectKey))
                .map(asset -> new StoredMediaFile(asset.getId(), asset.getOwnerId(), asset.getKind(),
                        asset.getBucket(), asset.getObjectKey(), asset.getMimeType(), asset.getBytes(),
                        mediaBucket.equals(asset.getBucket())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<BoundRunInput> boundInputs(UUID runId) {
        String mediaBucket = properties.s3().bucket();
        List<BoundRunInput> inputs = new ArrayList<>();
        for (MediaAssetBinding binding : bindings.findByRunIdOrderByPositionAsc(runId)) {
            Optional<MediaAsset> upload = assets.findById(binding.getAssetId());
            if (upload.isEmpty()) {
                LOG.warn("Bound upload has no asset row: runId={} bindingKey={} assetId={}", runId,
                        binding.getBindingKey(), binding.getAssetId());
                continue;
            }
            MediaAsset asset = upload.get();
            inputs.add(new BoundRunInput(asset.getId(), asset.getOwnerId(), binding.getBindingKey(),
                    asset.getKind(), mediaBucket, "runs/" + runId + "/inputs/" + binding.getBindingKey(),
                    asset.getMimeType()));
        }
        return inputs;
    }

    @Override
    public OptionalLong keepRunInput(String bucket, String objectKey) {
        if (!properties.s3().bucket().equals(bucket) || objectKey == null
                || !RUN_INPUT_KEY.matcher(objectKey).matches()) {
            throw new IllegalArgumentException("Only a run's input copy can be kept: " + bucket + "/" + objectKey);
        }
        try {
            Optional<S3MediaStore.StoredObject> copy = store.head(bucket, objectKey);
            if (copy.isEmpty()) {
                return OptionalLong.empty();
            }
            store.retag(bucket, objectKey, KEPT);
            return OptionalLong.of(copy.get().size());
        } catch (NoSuchKeyException e) {
            return OptionalLong.empty();
        } catch (SdkException e) {
            LOG.warn("Keeping a run input failed: bucket={} key={}", bucket, objectKey, e);
            throw new MediaStoreUnavailableException("The object store did not keep the run input", e);
        }
    }
}
