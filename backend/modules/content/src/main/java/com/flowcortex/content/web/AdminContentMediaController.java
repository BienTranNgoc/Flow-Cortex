package com.flowcortex.content.web;

import com.flowcortex.content.media.ContentMediaService;
import com.flowcortex.media.api.UploadGrant;
import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.security.authz.RequirePermission;
import com.flowcortex.shared.web.PageView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/admin/content/media} — staff upload files into the content library and browse it. The
 * permission each route needs is declared on its handler.
 */
@RestController
@RequestMapping("/api/v1/admin/content/media")
class AdminContentMediaController {

    private static final int MAX_PAGE = 100_000;
    private static final int MAX_PAGE_SIZE = 100;

    private final ContentMediaService media;
    private final ContentCaller caller;

    AdminContentMediaController(ContentMediaService media, ContentCaller caller) {
        this.media = media;
        this.caller = caller;
    }

    /** {@code fileName} only picks the type of the grant. */
    @Schema(name = "ContentMediaUploadRequest")
    record UploadRequest(@NotBlank @Size(max = 255) String fileName, @NotNull Long size) {
    }

    /** {@code fileName} is the name the item shows in the library; it carries no path separator or control character. */
    @Schema(name = "RegisterContentMediaRequest")
    record RegisterRequest(
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "The upload id of a granted, uploaded file")
            @NotNull UUID uploadId,
            @Schema(requiredMode = RequiredMode.REQUIRED)
            @NotBlank @Size(max = 255) @Pattern(regexp = "[^\\p{Cntrl}/\\\\]+") String fileName) {
    }

    @Operation(operationId = "requestContentMediaUpload")
    @ApiResponse(responseCode = "201")
    @ApiResponse(responseCode = "400", description = "content-media-rejected, validation-failed (errors)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "content-media-unavailable",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.CONTENT_MEDIA_MANAGE)
    @PostMapping(value = "/uploads", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<UploadGrant> requestUpload(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UploadRequest body) {
        UploadGrant grant = media.grant(caller.require(jwt), body.fileName(), body.size());
        return ResponseEntity.status(HttpStatus.CREATED).body(grant);
    }

    @Operation(operationId = "registerContentMedia")
    @ApiResponse(responseCode = "201")
    @ApiResponse(responseCode = "400", description = "content-media-rejected, validation-failed (errors)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "content-media-unavailable",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.CONTENT_MEDIA_MANAGE)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ContentMediaResponse> register(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody RegisterRequest body) {
        ContentMediaResponse item = ContentMediaResponse.of(
                media.register(caller.require(jwt), body.uploadId(), body.fileName()));
        return ResponseEntity.status(HttpStatus.CREATED).body(item);
    }

    @Operation(operationId = "listContentMedia")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "validation-failed (errors by parameter name)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.CONTENT_MEDIA_VIEW)
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    PageView<ContentMediaResponse> list(
            @RequestParam(required = false) @Pattern(regexp = "image|video") String kind,
            @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
            @RequestParam(defaultValue = "24") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return media.page(kind, page, size).map(ContentMediaResponse::of);
    }
}
