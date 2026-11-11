package com.flowcortex.profile.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.media.api.KeptImage;
import com.flowcortex.media.api.MediaDownload;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.UploadGrant;
import com.flowcortex.media.api.UploadPurpose;
import com.flowcortex.profile.application.ProfileImagesService.CurrentImages;
import com.flowcortex.profile.domain.ProfileImage;
import com.flowcortex.profile.domain.ProfileImage.Role;
import com.flowcortex.profile.domain.ProfileImage.Slot;
import com.flowcortex.profile.infrastructure.ProfileImageRepository;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.user.api.CurrentUser;
import com.flowcortex.user.api.UserDirectory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class ProfileImagesServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-11T00:00:00Z");
    private static final String BUCKET = "media";

    private final MediaJobs mediaJobs = mock(MediaJobs.class);
    private final ProfileImageRepository images = mock(ProfileImageRepository.class);
    private final UserDirectory users = mock(UserDirectory.class);
    private final UUID userId = UUID.randomUUID();
    private final UUID uploadId = UUID.randomUUID();

    private ProfileImagesService service;

    @BeforeEach
    void setUp() {
        service = new ProfileImagesService(mediaJobs, images, users, mock(PlatformTransactionManager.class),
                Clock.fixed(NOW, ZoneOffset.UTC), BUCKET);
        when(users.findById(userId)).thenReturn(Optional.of(mock(CurrentUser.class)));
        when(mediaJobs.presignedGet(eq(BUCKET), anyString(), anyString()))
                .thenReturn(new MediaDownload("https://signed", NOW.plusSeconds(600)));
    }

    @Test
    void grantAsksTheMediaEngineForAProfileImageUpload() {
        UploadGrant grant = new UploadGrant(uploadId, "https://put", "PUT", Map.of(), NOW, 10L);
        when(mediaJobs.isEnabled()).thenReturn(true);
        when(mediaJobs.prepareUpload(UploadPurpose.PROFILE_IMAGE, userId, "me.png", 10L)).thenReturn(grant);

        assertThat(service.grant(userId, "me.png", 10L)).isSameAs(grant);
    }

    @Test
    void grantIsUnavailableWhileMediaIsOff() {
        when(mediaJobs.isEnabled()).thenReturn(false);

        assertThatThrownBy(() -> service.grant(userId, "me.png", 10L)).isInstanceOf(LocalizedStatusException.class);
        verify(mediaJobs, never()).prepareUpload(any(), any(), any(), anyLong());
    }

    @Test
    void anUnknownUserIsNotFound() {
        UUID stranger = UUID.randomUUID();
        when(users.findById(stranger)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.current(stranger)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void currentSignsEachSlotAndLeavesAnEmptySlotNull() {
        ProfileImage avatar = stored(Slot.AVATAR, Role.CURRENT, "image/png");
        when(images.findCurrent(userId, Slot.AVATAR)).thenReturn(Optional.of(avatar));
        when(images.findCurrent(userId, Slot.COVER)).thenReturn(Optional.empty());

        CurrentImages current = service.current(userId);

        assertThat(current.avatar().id()).isEqualTo(avatar.id());
        assertThat(current.avatar().url()).isEqualTo("https://signed");
        assertThat(current.cover()).isNull();
        verify(mediaJobs).presignedGet(BUCKET, avatar.objectKey(), "avatar-" + avatar.id() + ".png");
    }

    @Test
    void originalsAreReadUpToTheHistoryLimit() {
        ProfileImage original = stored(Slot.COVER, Role.ORIGINAL, "application/octet-stream");
        when(images.findOriginalsNewestFirst(userId, Slot.COVER, ProfileImageRules.HISTORY_LIMIT))
                .thenReturn(List.of(original));

        assertThat(service.originals(userId, Slot.COVER)).extracting(ProfileImageView::id)
                .containsExactly(original.id());
        verify(mediaJobs).presignedGet(BUCKET, original.objectKey(), "cover-" + original.id() + ".img");
    }

    @Test
    void addOriginalKeepsTheUploadAndReleasesTheOriginalsBeyondTheHistory() {
        keepAs(512, 512);
        List<ProfileImage> history = new ArrayList<>();
        for (int i = 0; i <= ProfileImageRules.HISTORY_LIMIT; i++) {
            history.add(stored(Slot.AVATAR, Role.ORIGINAL, "image/png"));
        }
        when(images.findOriginalsNewestFirst(userId, Slot.AVATAR, Integer.MAX_VALUE)).thenReturn(history);

        ProfileImageView view = service.addOriginal(userId, Slot.AVATAR, uploadId);

        assertThat(view.width()).isEqualTo(512);
        verify(images).lockSlot(userId, Slot.AVATAR);
        verify(images).insert(any(ProfileImage.class));
        verify(mediaJobs).confirmProfileImages(List.of("kept-key"));
        verify(mediaJobs).releaseProfileImages(List.of(history.get(ProfileImageRules.HISTORY_LIMIT).objectKey()));
    }

    @Test
    void setCurrentReplacesThePreviousImageAndSurvivesAFailedRelease() {
        keepAs(1500, 500);
        ProfileImage previous = stored(Slot.COVER, Role.CURRENT, "image/webp");
        when(images.deleteCurrent(userId, Slot.COVER)).thenReturn(Optional.of(previous));
        doThrow(new IllegalStateException("s3 down")).when(mediaJobs).releaseProfileImages(any());

        ProfileImageView view = service.setCurrent(userId, Slot.COVER, uploadId);

        assertThat(view.height()).isEqualTo(500);
        verify(mediaJobs).keepProfileImage(eq(userId), eq(uploadId), startsWith("profile/" + userId + "/cover/"));
        verify(mediaJobs).releaseProfileImages(List.of(previous.objectKey()));
    }

    @Test
    void setCurrentWithNoPreviousImageReleasesNothingAndSurvivesAFailedConfirm() {
        keepAs(256, 256);
        when(images.deleteCurrent(userId, Slot.AVATAR)).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("s3 down")).when(mediaJobs).confirmProfileImages(any());

        assertThat(service.setCurrent(userId, Slot.AVATAR, uploadId).width()).isEqualTo(256);
        verify(mediaJobs, never()).releaseProfileImages(any());
    }

    @Test
    void setCurrentRefusesAShapeThatDoesNotFitTheSlot() {
        keepAs(1500, 500);

        assertThatThrownBy(() -> service.setCurrent(userId, Slot.AVATAR, uploadId))
                .isInstanceOf(UnprocessableEntityException.class);
        verify(images, never()).insert(any());
    }

    private void keepAs(int width, int height) {
        when(mediaJobs.keepProfileImage(eq(userId), eq(uploadId), anyString()))
                .thenReturn(new KeptImage(BUCKET, "kept-key", "image/png", 100L, width, height));
    }

    private ProfileImage stored(Slot slot, Role role, String mimeType) {
        UUID id = UUID.randomUUID();
        return new ProfileImage(id, userId, slot, role, "profile/" + userId + "/" + slot.wire() + "/" + id, mimeType,
                512, 512, 100L, NOW);
    }
}
