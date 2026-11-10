package com.flowcortex.app.clienterrors;

import com.flowcortex.shared.ratelimit.RateLimiter;
import com.flowcortex.shared.ratelimit.RateLimiters;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public intake for errors the web apps contain. Posted as {@code text/plain} so it stays a simple
 * cross-origin request and Jackson never binds the body leniently; the schema is checked by hand.
 * Order of checks: switch, per-address limit, media type, size, JSON, schema.
 */
@RestController
@RequestMapping("/api/v1")
class ClientErrorsController {

    static final int MAX_BODY_BYTES = 8192;

    private static final Logger LOG = LoggerFactory.getLogger(ClientErrorsController.class);

    private final boolean enabled;
    private final RateLimiter addressLimiter;
    private final ClientErrorReportValidator validator;
    private final ClientErrorTelemetry telemetry;

    ClientErrorsController(ClientErrorsProperties properties, RateLimiters rateLimiters,
            ClientErrorReportValidator validator, ClientErrorTelemetry telemetry) {
        this.enabled = properties.enabled();
        this.addressLimiter = rateLimiters.named(ClientErrorRateLimits.CLIENT_ERRORS_IP);
        this.validator = validator;
        this.telemetry = telemetry;
    }

    @Operation(summary = "Report an error the web app contained")
    @SecurityRequirements
    @RequestBody(required = true, content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE,
            schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "204", description = "Accepted, or ignored while the intake is off")
    @ApiResponse(responseCode = "400", description = "client_error_content_type, client_error_unreadable, client_error_invalid",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "413", description = "client_error_too_large",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "client_error_rate_limited (Retry-After)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/client-errors")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reportClientError(HttpServletRequest request) {
        if (!enabled) {
            return;
        }
        Optional<Duration> wait = addressLimiter.tryAcquire(request.getRemoteAddr());
        if (wait.isPresent()) {
            throw new ClientErrorsRateLimitedException(wait.get());
        }
        if (!isPlainText(request.getContentType())) {
            throw new ClientErrorRefusedException(ClientErrorRefusal.CONTENT_TYPE);
        }
        ClientErrorReportValidator.Result result = validator.validate(readBody(request));
        if (result.refusal() != null) {
            throw new ClientErrorRefusedException(result.refusal());
        }
        telemetry.accepted(ClientErrorRedaction.redact(result.report()));
    }

    private static boolean isPlainText(String contentType) {
        if (contentType == null) {
            return false;
        }
        try {
            return MediaType.TEXT_PLAIN.equalsTypeAndSubtype(MediaType.parseMediaType(contentType));
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }

    /** Reads at most one byte past the limit, so the length header is never trusted and no oversize body is buffered. */
    private static String readBody(HttpServletRequest request) {
        byte[] bytes;
        try {
            bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        } catch (IOException e) {
            LOG.debug("client_error_body_unreadable", e);
            throw new ClientErrorRefusedException(ClientErrorRefusal.UNREADABLE);
        }
        if (bytes.length > MAX_BODY_BYTES) {
            throw new ClientErrorRefusedException(ClientErrorRefusal.TOO_LARGE);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new ClientErrorRefusedException(ClientErrorRefusal.UNREADABLE);
        }
    }
}
