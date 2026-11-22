package com.flowcortex.profile.web;

import com.flowcortex.media.api.UploadGrant;
import com.flowcortex.profile.application.ProfileImageView;
import com.flowcortex.profile.application.ProfileImagesService;
import com.flowcortex.profile.application.ProfileImagesService.CurrentImages;
import com.flowcortex.profile.domain.ProfileImage.Slot;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/profile/images}: the caller's own avatar and cover. No route takes a user id, so no
 * member can read or change another member's images.
 */
@RestController
@RequestMapping("/api/v1/profile/images")
class ProfileImagesController {

    private final ProfileImagesService images;

    ProfileImagesController(ProfileImagesService images) {
        this.images = images;
    }

    @PostMapping(value = "/uploads", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    UploadResponse requestUpload(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UploadRequest request) {
        return UploadResponse.from(images.grant(subjectId(jwt), request.fileName(), request.size()));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    CurrentImages current(@AuthenticationPrincipal Jwt jwt) {
        return images.current(subjectId(jwt));
    }

    @GetMapping(value = "/{slot}/originals", produces = MediaType.APPLICATION_JSON_VALUE)
    List<ProfileImageView> originals(@AuthenticationPrincipal Jwt jwt, @PathVariable String slot) {
        return images.originals(subjectId(jwt), slotOf(slot));
    }

    @PostMapping(value = "/{slot}/originals", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    ProfileImageView addOriginal(@AuthenticationPrincipal Jwt jwt, @PathVariable String slot,
            @Valid @RequestBody UploadedImageRequest request) {
        return images.addOriginal(subjectId(jwt), slotOf(slot), request.uploadId());
    }

    @PutMapping(value = "/{slot}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ProfileImageView setCurrent(@AuthenticationPrincipal Jwt jwt, @PathVariable String slot,
            @Valid @RequestBody UploadedImageRequest request) {
        return images.setCurrent(subjectId(jwt), slotOf(slot), request.uploadId());
    }

    private static Slot slotOf(String slot) {
        return Slot.fromWire(slot)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("profile.image.slotNotFound")));
    }

    private static UUID subjectId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    /** {@code fileName} only picks the type; it is never stored. */
    record UploadRequest(@NotBlank @Size(max = 255) String fileName, @NotNull @Positive Long size) {
    }

    record UploadedImageRequest(@NotNull UUID uploadId) {
    }

    /** PUT the file to {@code url} with exactly {@code headers} and no {@code Authorization} header. */
    record UploadResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID uploadId,
            @Schema(requiredMode = RequiredMode.REQUIRED) String url,
            @Schema(requiredMode = RequiredMode.REQUIRED) String method,
            @Schema(requiredMode = RequiredMode.REQUIRED) Map<String, String> headers,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant expiresAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) long maxBytes) {

        static UploadResponse from(UploadGrant grant) {
            return new UploadResponse(grant.uploadId(), grant.url(), grant.method(), grant.headers(),
                    grant.expiresAt(), grant.maxBytes());
        }
    }
}
