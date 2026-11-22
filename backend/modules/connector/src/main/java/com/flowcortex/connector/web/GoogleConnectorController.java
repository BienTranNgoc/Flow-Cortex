package com.flowcortex.connector.web;

import com.flowcortex.connector.application.google.GoogleConnectorService;
import com.flowcortex.connector.application.google.GoogleConnectorService.AuthorizeStart;
import com.flowcortex.connector.application.google.GoogleConnectorService.GoogleConnection;
import com.flowcortex.connector.application.google.GoogleConnectorService.GoogleHealth;
import com.flowcortex.connector.application.google.GoogleOAuthRules;
import com.flowcortex.connector.application.google.GoogleProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/connectors/google} — a member's own Google account. The callback is the one route without a
 * token: the sealed flow cookie that {@code authorize} set is its credential, and the browser is always sent back
 * to the configured return path, never to an address taken from the request. No response carries a token.
 */
@RestController
@RequestMapping("/api/v1/connectors/google")
class GoogleConnectorController {

    static final String CALLBACK_PATH = "/api/v1/connectors/google/callback";
    static final String FLOW_COOKIE = "connector_google_flow";

    record GoogleAuthorizeResponse(String authorizationUrl) {
    }

    private final GoogleConnectorService google;
    private final GoogleProperties properties;
    private final ConnectorMember member;

    GoogleConnectorController(GoogleConnectorService google, GoogleProperties properties, ConnectorMember member) {
        this.google = google;
        this.properties = properties;
        this.member = member;
    }

    @Operation(operationId = "googleAuthorize")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "403", description = "Member is suspended (SUSPENDED)", content = @Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Connector switched off (TURNED_OFF)", content = @Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/authorize")
    ResponseEntity<GoogleAuthorizeResponse> googleAuthorize(@AuthenticationPrincipal Jwt jwt) {
        AuthorizeStart start = google.authorize(member.id(jwt));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, flowCookie(start.sealedFlow(), GoogleOAuthRules.FLOW_TTL).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new GoogleAuthorizeResponse(start.url()));
    }

    @Operation(operationId = "googleCallback")
    @SecurityRequirements
    @ApiResponse(responseCode = "302", description = "Redirect to the return path with connector=google and result",
            headers = @Header(name = HttpHeaders.LOCATION, description = "The return path with the result",
                    schema = @Schema(type = "string")))
    @GetMapping("/callback")
    ResponseEntity<Void> googleCallback(@CookieValue(name = FLOW_COOKIE, required = false) String flow,
            @RequestParam(required = false) String state, @RequestParam(required = false) String code,
            @RequestParam(required = false) String error) {
        String result = google.callback(flow, state, code, error);
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.SET_COOKIE, flowCookie("", Duration.ZERO).toString())
                .header(HttpHeaders.LOCATION, properties.returnPath() + "?connector=google&result=" + result)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Referrer-Policy", "no-referrer")
                .build();
    }

    @Operation(operationId = "googleRead")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "403", description = "Member is suspended (SUSPENDED)", content = @Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No active Google connection (NOT_CONNECTED)", content = @Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Connector switched off (TURNED_OFF)", content = @Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping
    GoogleConnection googleRead(@AuthenticationPrincipal Jwt jwt) {
        return google.read(member.id(jwt));
    }

    @Operation(operationId = "googleHealth")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "403", description = "Member is suspended (SUSPENDED)", content = @Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No active Google connection (NOT_CONNECTED)", content = @Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "502", description = "Google unreachable (UPSTREAM_UNAVAILABLE)", content = @Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Connector switched off (TURNED_OFF)", content = @Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/health")
    GoogleHealth googleHealth(@AuthenticationPrincipal Jwt jwt) {
        return google.health(member.id(jwt));
    }

    private static ResponseCookie flowCookie(String value, Duration maxAge) {
        return ResponseCookie.from(FLOW_COOKIE, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path(CALLBACK_PATH)
                .maxAge(maxAge)
                .build();
    }
}
