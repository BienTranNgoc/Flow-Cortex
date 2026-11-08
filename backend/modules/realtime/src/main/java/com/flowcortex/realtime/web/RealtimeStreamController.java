package com.flowcortex.realtime.web;

import com.flowcortex.realtime.api.TopicAuthorizer;
import com.flowcortex.realtime.application.RealtimeProperties;
import com.flowcortex.realtime.application.SseHub;
import com.flowcortex.realtime.application.SseHub.Admission;
import com.flowcortex.realtime.application.TopicAccess;
import com.flowcortex.realtime.application.TopicAccess.Outcome;
import com.flowcortex.realtime.application.TopicAccess.Status;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.UserDirectory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code GET /api/v1/realtime/stream}: the member's Server-Sent Events stream. Every refusal
 * (401, 400, 404, 429) is decided before the response starts, so it is problem+json and never
 * bytes inside an event stream. The emitter holds no thread; its timeout is the token's time left.
 */
@RestController
@RequestMapping("/api/v1/realtime")
class RealtimeStreamController {

    private final UserDirectory userDirectory;
    private final ObjectProvider<TopicAuthorizer> authorizers;
    private final SseHub hub;
    private final RealtimeProperties properties;

    RealtimeStreamController(UserDirectory userDirectory, ObjectProvider<TopicAuthorizer> authorizers, SseHub hub,
            RealtimeProperties properties) {
        this.userDirectory = userDirectory;
        this.authorizers = authorizers;
        this.hub = hub;
        this.properties = properties;
    }

    @Operation(operationId = "openRealtimeStream")
    @ApiResponse(responseCode = "200", description = "Server-Sent Events stream",
            content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE, schema = @Schema(implementation = String.class)))
    @ApiResponse(responseCode = "400", description = "invalid-topics (none, too many, malformed)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "topic-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "stream-limit-reached",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> stream(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "topic", required = false) List<String> topics,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        UUID userId = currentUserId(jwt);
        Outcome outcome = TopicAccess.resolve(userId, topics, properties.maxTopicsPerStream(),
                authorizers.orderedStream().toList());
        if (outcome.status() == Status.INVALID) {
            throw new LocalizedStatusException(HttpStatus.BAD_REQUEST,
                    new LocalizedMessage("realtime.topic.invalid", properties.maxTopicsPerStream()));
        }
        if (outcome.status() == Status.NOT_FOUND) {
            throw new NotFoundException(new LocalizedMessage("realtime.topic.notFound"));
        }

        Instant expiresAt = jwt.getExpiresAt();
        if (expiresAt == null) {
            throw new InvalidBearerTokenException("The access token has no expiry");
        }
        SseEmitter emitter = new SseEmitter(Math.max(1, Duration.between(Instant.now(), expiresAt).toMillis()));
        Admission admission = hub.register(userId, outcome.topics(), expiresAt, lastEventId, new SseEmitterSink(emitter));
        if (admission == Admission.LIMIT_REACHED) {
            throw new LocalizedStatusException(HttpStatus.TOO_MANY_REQUESTS, new LocalizedMessage("realtime.stream.limit"));
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Accel-Buffering", "no")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }

    /** A token can outlive the row it was minted for; that is 401 here, not the 404 other modules answer. */
    private UUID currentUserId(Jwt jwt) {
        UUID id;
        try {
            id = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidBearerTokenException("The access token subject is not a user id");
        }
        return userDirectory.findById(id)
                .orElseThrow(() -> new LocalizedStatusException(HttpStatus.UNAUTHORIZED,
                        new LocalizedMessage("realtime.user.unknown")))
                .id();
    }
}
