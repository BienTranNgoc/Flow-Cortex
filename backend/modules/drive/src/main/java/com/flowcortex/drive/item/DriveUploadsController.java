package com.flowcortex.drive.item;

import com.flowcortex.drive.web.DriveCaller;
import com.flowcortex.drive.web.DriveCaller.Caller;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.UploadGrant;
import com.flowcortex.media.api.UploadPurpose;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/drive/uploads} — a presigned PUT for one Drive file, keyed under the caller. The upload id
 * it returns is what {@code POST /api/v1/drive/items} is then called with.
 */
@RestController
@RequestMapping("/api/v1/drive/uploads")
class DriveUploadsController {

    private final DriveCaller driveCaller;
    private final MediaJobs mediaJobs;

    DriveUploadsController(DriveCaller driveCaller, MediaJobs mediaJobs) {
        this.driveCaller = driveCaller;
        this.mediaJobs = mediaJobs;
    }

    @Operation(operationId = "requestDriveUpload")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<DriveUploadResponse> requestUpload(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody DriveUploadRequest request) {
        Caller caller = driveCaller.require(jwt);
        if (!mediaJobs.isEnabled()) {
            throw new LocalizedStatusException(HttpStatus.SERVICE_UNAVAILABLE, new LocalizedMessage("media.disabled"));
        }
        UploadGrant grant = mediaJobs.prepareUpload(UploadPurpose.DRIVE_ITEM, caller.userId(), request.fileName(),
                request.size());
        return ResponseEntity.status(HttpStatus.CREATED).body(DriveUploadResponse.from(grant));
    }

    /** {@code fileName} only picks the type; it is never stored. */
    record DriveUploadRequest(@NotBlank @Size(max = 255) String fileName, @NotNull Long size) {
    }

    /** PUT the file to {@code url} with exactly {@code headers} and no {@code Authorization} header. */
    record DriveUploadResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID uploadId,
            @Schema(requiredMode = RequiredMode.REQUIRED) String url,
            @Schema(requiredMode = RequiredMode.REQUIRED) String method,
            @Schema(requiredMode = RequiredMode.REQUIRED) Map<String, String> headers,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant expiresAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) long maxBytes) {

        static DriveUploadResponse from(UploadGrant grant) {
            return new DriveUploadResponse(grant.uploadId(), grant.url(), grant.method(), grant.headers(),
                    grant.expiresAt(), grant.maxBytes());
        }
    }
}
