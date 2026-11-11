package com.flowcortex.profile.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.media.api.UploadGrant;
import com.flowcortex.media.api.UploadRejectedException;
import com.flowcortex.profile.application.ProfileImageView;
import com.flowcortex.profile.application.ProfileImagesService;
import com.flowcortex.profile.application.ProfileImagesService.CurrentImages;
import com.flowcortex.profile.domain.ProfileImage.Slot;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.servlet.i18n.FixedLocaleResolver;

class ProfileImagesControllerTest {

    private static final Instant NOW = Instant.parse("2026-11-11T00:00:00Z");

    private final ProfileImagesService service = mock(ProfileImagesService.class);
    private final ProfileImagesController controller = new ProfileImagesController(service);
    private final UUID userId = UUID.randomUUID();
    private final Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(userId.toString()).build();
    private final ProfileImageView view = new ProfileImageView(UUID.randomUUID(), "https://signed", NOW, 512, 512);

    @Test
    void requestUploadAnswersWithTheGrant() {
        UUID uploadId = UUID.randomUUID();
        when(service.grant(userId, "me.png", 10L))
                .thenReturn(new UploadGrant(uploadId, "https://put", "PUT", Map.of("a", "b"), NOW, 10L));

        ProfileImagesController.UploadResponse response =
                controller.requestUpload(jwt, new ProfileImagesController.UploadRequest("me.png", 10L));

        assertThat(response.uploadId()).isEqualTo(uploadId);
        assertThat(response.headers()).containsEntry("a", "b");
        assertThat(response.maxBytes()).isEqualTo(10L);
    }

    @Test
    void readsAndWritesTheCallersOwnSlots() {
        UUID uploadId = UUID.randomUUID();
        CurrentImages current = new CurrentImages(view, null);
        when(service.current(userId)).thenReturn(current);
        when(service.originals(userId, Slot.COVER)).thenReturn(List.of(view));
        when(service.addOriginal(userId, Slot.AVATAR, uploadId)).thenReturn(view);
        when(service.setCurrent(userId, Slot.COVER, uploadId)).thenReturn(view);
        ProfileImagesController.UploadedImageRequest request =
                new ProfileImagesController.UploadedImageRequest(uploadId);

        assertThat(controller.current(jwt)).isSameAs(current);
        assertThat(controller.originals(jwt, "cover")).containsExactly(view);
        assertThat(controller.addOriginal(jwt, "avatar", request)).isSameAs(view);
        assertThat(controller.setCurrent(jwt, "cover", request)).isSameAs(view);
        verify(service).setCurrent(userId, Slot.COVER, uploadId);
    }

    @Test
    void anUnknownSlotIsNotFound() {
        assertThatThrownBy(() -> controller.originals(jwt, "banner")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aRefusedImageIsReportedOnTheSingularUploadField() {
        StaticMessageSource messages = new StaticMessageSource();
        messages.addMessage("problem.validationFailed", Locale.ENGLISH, "Validation failed");
        messages.addMessage("media.upload.notImage", Locale.ENGLISH, "Not an image");
        ProfileImageRejectionHandler handler =
                new ProfileImageRejectionHandler(messages, new FixedLocaleResolver(Locale.ENGLISH));

        ProblemDetail detail = handler.rejected(
                new UploadRejectedException("uploadIds", new LocalizedMessage("media.upload.notImage")),
                new MockHttpServletRequest());

        assertThat(detail.getStatus()).isEqualTo(400);
        assertThat(detail.getDetail()).isEqualTo("Validation failed");
        assertThat(detail.getProperties()).containsEntry("errors", Map.of("uploadId", "Not an image"));
    }
}
