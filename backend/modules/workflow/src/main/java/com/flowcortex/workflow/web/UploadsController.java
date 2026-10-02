package com.flowcortex.workflow.web;

import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.UploadGrant;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code POST /api/v1/uploads} — a presigned PUT for one media file, keyed under the caller. The
 * file goes straight to the object store; its upload id is what a run is then started with.
 */
@RestController
@RequestMapping("/api/v1/uploads")
class UploadsController {

    private final MediaJobs mediaJobs;
    private final CurrentUserResolver currentUserResolver;

    UploadsController(MediaJobs mediaJobs, CurrentUserResolver currentUserResolver) {
        this.mediaJobs = mediaJobs;
        this.currentUserResolver = currentUserResolver;
    }

    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<UploadResponse> requestUpload(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UploadRequest request) {
        UUID callerId = currentUserResolver.require(jwt);
        if (!mediaJobs.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Media engine is currently disabled");
        }
        UploadGrant grant = mediaJobs.prepareUpload(callerId, request.fileName(), request.size());
        return ResponseEntity.status(HttpStatus.CREATED).body(UploadResponse.from(grant));
    }

    /** {@code fileName} only picks the type; it is never stored. */
    record UploadRequest(@NotBlank @Size(max = 255) String fileName, @NotNull Long size) {
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
