package com.flowcortex.user.oauth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.flowcortex.user.oauth.AuthorizationRequestCheck.Params;
import com.flowcortex.user.oauth.McpOAuthService.CheckView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two session-authenticated routes behind the consent page: check an authorization request, then
 * grant it. Errors are problem+json like the rest of the member app API; a token-authenticated
 * caller is refused by the route policy before either runs.
 */
@RestController
@RequestMapping("/api/v1/oauth/authorize")
class OAuthConsentController {

    private static final int USER_AGENT_MAX = 512;

    private final McpOAuthService service;

    OAuthConsentController(McpOAuthService service) {
        this.service = service;
    }

    record CheckResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String clientId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String clientName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String redirectUri,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String scope,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean eligible,
            @Schema(description = "The OAuth error the page sends to the redirect URI; null when the request is valid",
                    nullable = true) String error) {

        static CheckResponse from(CheckView view) {
            return new CheckResponse(view.clientId(), view.clientName(), view.redirectUri(), view.scope(),
                    view.eligible(), view.error());
        }
    }

    record GrantResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String redirectTo) {
    }

    /** The checked request's fields under their OAuth names, plus the proof the grant spends. */
    record GrantRequest(
            @JsonProperty("response_type") @Size(max = 32) String responseType,
            @JsonProperty("client_id") @Size(max = 64) String clientId,
            @JsonProperty("redirect_uri") @Size(max = 2048) String redirectUri,
            @JsonProperty("code_challenge") @Size(max = 256) String codeChallenge,
            @JsonProperty("code_challenge_method") @Size(max = 16) String codeChallengeMethod,
            @Size(max = 64) String scope,
            @Size(max = 1024) String state,
            @Size(max = 2048) String resource,
            @Size(max = 128) String stepUpProof) {

        Params toParams() {
            return new Params(responseType, clientId, redirectUri, codeChallenge, codeChallengeMethod, scope, state,
                    resource);
        }
    }

    @Operation(operationId = "checkOAuthAuthorization")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "oauth.invalidClient or oauth.invalidRedirectUri",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(path = "/request", produces = MediaType.APPLICATION_JSON_VALUE)
    CheckResponse check(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "response_type", required = false) @Size(max = 32) String responseType,
            @RequestParam(name = "client_id", required = false) @Size(max = 64) String clientId,
            @RequestParam(name = "redirect_uri", required = false) @Size(max = 2048) String redirectUri,
            @RequestParam(name = "code_challenge", required = false) @Size(max = 256) String codeChallenge,
            @RequestParam(name = "code_challenge_method", required = false) @Size(max = 16) String codeChallengeMethod,
            @RequestParam(name = "scope", required = false) @Size(max = 64) String scope,
            @RequestParam(name = "state", required = false) @Size(max = 1024) String state,
            @RequestParam(name = "resource", required = false) @Size(max = 2048) String resource) {
        Params params = new Params(responseType, clientId, redirectUri, codeChallenge, codeChallengeMethod, scope,
                state, resource);
        return CheckResponse.from(service.check(UUID.fromString(jwt.getSubject()), params));
    }

    @Operation(operationId = "grantOAuthAuthorization")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "oauth.invalidClient, oauth.invalidRedirectUri or oauth.invalidRequest",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "PAT_NOT_ELIGIBLE, STEP_UP_REQUIRED or PAT_NOT_ALLOWED",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "PAT_LIMIT_REACHED",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    GrantResponse grant(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody GrantRequest request,
            HttpServletRequest servletRequest) {
        String redirectTo = service.grant(UUID.fromString(jwt.getSubject()), request.toParams(),
                request.stepUpProof(), servletRequest.getRemoteAddr(), userAgent(servletRequest));
        return new GrantResponse(redirectTo);
    }

    private static String userAgent(HttpServletRequest request) {
        String agent = request.getHeader(HttpHeaders.USER_AGENT);
        return agent == null || agent.length() <= USER_AGENT_MAX ? agent : agent.substring(0, USER_AGENT_MAX);
    }
}
