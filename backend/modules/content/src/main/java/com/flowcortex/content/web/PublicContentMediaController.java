package com.flowcortex.content.web;

import com.flowcortex.content.media.ContentMediaService;
import com.flowcortex.media.api.MediaDownload;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/content/media/{id}} — anyone follows a library item's URL to a time-limited link to its
 * stored copy. The redirect may be cached for five minutes, shorter than the link it carries lives.
 */
@RestController
@RequestMapping(PublicContentMediaController.PATH)
@SecurityRequirements
class PublicContentMediaController {

    static final String PATH = "/api/v1/content/media";
    static final String CACHE_CONTROL = "public, max-age=300";

    private final ContentMediaService media;

    PublicContentMediaController(ContentMediaService media) {
        this.media = media;
    }

    @Operation(operationId = "getContentMedia")
    @ApiResponse(responseCode = "302", description = "Redirect to a time-limited URL of the stored copy")
    @ApiResponse(responseCode = "404", description = "media-not-found (also a malformed id)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{id}")
    ResponseEntity<Void> redirect(@PathVariable String id) {
        MediaDownload download = media.download(parse(id));
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(download.url()))
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .build();
    }

    /** An id that is not a UUID names no item, so it is 404 like an unknown one rather than 400. */
    private static UUID parse(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw ContentProblemException.mediaNotFound();
        }
    }
}
