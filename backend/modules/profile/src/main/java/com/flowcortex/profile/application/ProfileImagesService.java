package com.flowcortex.profile.application;

import com.flowcortex.media.api.KeptImage;
import com.flowcortex.media.api.MediaDownload;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.UploadGrant;
import com.flowcortex.media.api.UploadPurpose;
import com.flowcortex.media.api.UploadRejectedException;
import com.flowcortex.profile.domain.ProfileImage;
import com.flowcortex.profile.domain.ProfileImage.Role;
import com.flowcortex.profile.domain.ProfileImage.Slot;
import com.flowcortex.profile.infrastructure.ProfileImageRepository;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.user.api.UserDirectory;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A member's avatar and cover: the history of hardened originals and the current image of each slot.
 * <p>
 * Every write keeps the hardened copy first, outside a transaction, under an object tag that expires it; the
 * database write then runs under a per-slot lock, and only after it commits is the new object confirmed and the
 * dropped ones released. A failure before the commit costs at most one object that expires by itself.
 */
@Service
public class ProfileImagesService {

    private static final Logger LOG = LoggerFactory.getLogger(ProfileImagesService.class);
    private static final String SHAPE_CODE = "PROFILE_IMAGE_SHAPE";
    private static final Map<String, String> EXTENSION_OF_TYPE = Map.of(
            "image/png", "png", "image/jpeg", "jpg", "image/webp", "webp");

    /** The caller's current avatar and cover; a slot with no image is {@code null}. */
    public record CurrentImages(ProfileImageView avatar, ProfileImageView cover) {
    }

    private final MediaJobs mediaJobs;
    private final ProfileImageRepository images;
    private final UserDirectory users;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final String mediaBucket;

    ProfileImagesService(MediaJobs mediaJobs, ProfileImageRepository images, UserDirectory users,
            PlatformTransactionManager transactionManager, Clock clock,
            @Value("${flowcortex.media.s3.bucket}") String mediaBucket) {
        this.mediaJobs = mediaJobs;
        this.images = images;
        this.users = users;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.mediaBucket = mediaBucket;
    }

    /** @throws UploadRejectedException on field {@code fileName} or {@code size} the profile-image purpose refuses */
    public UploadGrant grant(UUID userId, String fileName, long size) {
        requireActive(userId);
        if (!mediaJobs.isEnabled()) {
            throw new LocalizedStatusException(HttpStatus.SERVICE_UNAVAILABLE, new LocalizedMessage("media.disabled"));
        }
        return mediaJobs.prepareUpload(UploadPurpose.PROFILE_IMAGE, userId, fileName, size);
    }

    public CurrentImages current(UUID userId) {
        requireActive(userId);
        return new CurrentImages(images.findCurrent(userId, Slot.AVATAR).map(this::view).orElse(null),
                images.findCurrent(userId, Slot.COVER).map(this::view).orElse(null));
    }

    public List<ProfileImageView> originals(UUID userId, Slot slot) {
        requireActive(userId);
        return images.findOriginalsNewestFirst(userId, slot, ProfileImageRules.HISTORY_LIMIT).stream()
                .map(this::view).toList();
    }

    /**
     * Keeps the upload as the newest original of {@code slot}; the 31st original removes the oldest.
     * @throws UploadRejectedException if the upload is missing, not the caller's, or not an image
     */
    public ProfileImageView addOriginal(UUID userId, Slot slot, UUID uploadId) {
        requireActive(userId);
        ProfileImage image = keep(userId, slot, Role.ORIGINAL, uploadId);
        List<ProfileImage> dropped = transaction.execute(status -> {
            images.lockSlot(userId, slot);
            images.insert(image);
            List<ProfileImage> beyond = ProfileImageRules.beyondHistory(
                    images.findOriginalsNewestFirst(userId, slot, Integer.MAX_VALUE));
            images.deleteAll(beyond.stream().map(ProfileImage::id).toList());
            return beyond;
        });
        afterCommit(image, dropped);
        return view(image);
    }

    /**
     * Makes the upload the current image of {@code slot} and releases the previous one.
     * @throws UploadRejectedException if the upload is missing, not the caller's, or not an image
     * @throws UnprocessableEntityException if the image's shape does not fit the slot
     */
    public ProfileImageView setCurrent(UUID userId, Slot slot, UUID uploadId) {
        requireActive(userId);
        ProfileImage image = keep(userId, slot, Role.CURRENT, uploadId);
        if (!ProfileImageRules.fits(slot, image.width(), image.height())) {
            LOG.info("profile.image outcome=shape_refused slot={} width={} height={}", slot.wire(), image.width(),
                    image.height());
            LocalizedMessage mismatch = new LocalizedMessage("profile.image.shapeMismatch");
            throw new UnprocessableEntityException(mismatch, SHAPE_CODE, Map.of("uploadId", mismatch));
        }
        Optional<ProfileImage> replaced = transaction.execute(status -> {
            images.lockSlot(userId, slot);
            Optional<ProfileImage> previous = images.deleteCurrent(userId, slot);
            images.insert(image);
            return previous;
        });
        afterCommit(image, replaced.stream().toList());
        return view(image);
    }

    private ProfileImage keep(UUID userId, Slot slot, Role role, UUID uploadId) {
        UUID imageId = UUID.randomUUID();
        String key = "profile/" + userId + "/" + slot.wire() + "/" + imageId;
        KeptImage kept = mediaJobs.keepProfileImage(userId, uploadId, key);
        return new ProfileImage(imageId, userId, slot, role, kept.objectKey(), kept.contentType(), kept.width(),
                kept.height(), kept.bytes(), clock.instant());
    }

    /** Never throws: the answer is already decided and a leftover object costs storage or a placeholder only. */
    private void afterCommit(ProfileImage saved, List<ProfileImage> removed) {
        try {
            mediaJobs.confirmProfileImages(List.of(saved.objectKey()));
        } catch (RuntimeException e) {
            LOG.warn("profile.image outcome=confirm_failed id={} error={}", saved.id(), e.getClass().getSimpleName());
        }
        if (removed.isEmpty()) {
            return;
        }
        List<String> keys = new ArrayList<>(removed.stream().map(ProfileImage::objectKey).toList());
        try {
            mediaJobs.releaseProfileImages(keys);
        } catch (RuntimeException e) {
            LOG.warn("profile.image outcome=release_failed count={} error={}", keys.size(),
                    e.getClass().getSimpleName());
        }
    }

    private ProfileImageView view(ProfileImage image) {
        String extension = EXTENSION_OF_TYPE.getOrDefault(image.mimeType(), "img");
        MediaDownload download = mediaJobs.presignedGet(mediaBucket, image.objectKey(),
                image.slot().wire() + "-" + image.id() + "." + extension);
        return new ProfileImageView(image.id(), download.url(), download.expiresAt(), image.width(), image.height());
    }

    /** The user directory refuses a suspended account with 403; a token can outlive its row (404). */
    private void requireActive(UUID userId) {
        users.findById(userId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("user.current.notFound", userId)));
    }
}
