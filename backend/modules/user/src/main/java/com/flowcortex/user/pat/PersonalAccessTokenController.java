package com.flowcortex.user.pat;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.flowcortex.user.pat.PersonalAccessTokenService.CreatedToken;
import com.flowcortex.user.pat.PersonalAccessTokenService.TokenSummary;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/me/api-tokens}: the signed-in admin lists, creates and revokes their own personal
 * access tokens. Creating needs a fresh step-up proof; the raw token is in the 201 response only.
 */
@RestController
@RequestMapping("/api/v1/me/api-tokens")
class PersonalAccessTokenController {

    private static final int USER_AGENT_MAX = 512;

    private final PersonalAccessTokenService service;

    PersonalAccessTokenController(PersonalAccessTokenService service) {
        this.service = service;
    }

    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "403", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    List<ApiTokenView> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(UUID.fromString(jwt.getSubject())).stream().map(ApiTokenView::from).toList();
    }

    @ApiResponse(responseCode = "201")
    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<CreatedApiToken> create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateApiTokenRequest request, HttpServletRequest servletRequest) {
        CreatedToken created = service.create(UUID.fromString(jwt.getSubject()), request.getName(),
                request.getExpiresInDays(), request.getStepUpProof(), servletRequest.getRemoteAddr(),
                userAgent(servletRequest));
        PersonalAccessToken token = created.token();
        return ResponseEntity.status(HttpStatus.CREATED).body(new CreatedApiToken(token.getId(), token.getName(),
                created.rawToken(), token.getCreatedAt(), token.getExpiresAt()));
    }

    @ApiResponse(responseCode = "204")
    @ApiResponse(responseCode = "403", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @DeleteMapping("/{id}")
    ResponseEntity<Void> revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            HttpServletRequest servletRequest) {
        service.revoke(UUID.fromString(jwt.getSubject()), id, servletRequest.getRemoteAddr(),
                userAgent(servletRequest));
        return ResponseEntity.noContent().build();
    }

    private static String userAgent(HttpServletRequest request) {
        String agent = request.getHeader(HttpHeaders.USER_AGENT);
        return agent == null || agent.length() <= USER_AGENT_MAX ? agent : agent.substring(0, USER_AGENT_MAX);
    }

    /**
     * {@code expiresInDays}: absent means the default lifetime; an explicit JSON {@code null} is read
     * as 0 so the minimum rejects it, because a token that never expires is not offered. A setter, not
     * a record component: Jackson applies the null handling to an absent constructor argument too.
     */
    static final class CreateApiTokenRequest {

        @NotBlank
        @Size(max = 128)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String name;

        @Min(1)
        @Max(365)
        @Schema(nullable = false)
        private Integer expiresInDays;

        @Size(max = 128)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String stepUpProof;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Integer getExpiresInDays() {
            return expiresInDays;
        }

        @JsonSetter(nulls = Nulls.AS_EMPTY)
        public void setExpiresInDays(Integer expiresInDays) {
            this.expiresInDays = expiresInDays;
        }

        public String getStepUpProof() {
            return stepUpProof;
        }

        public void setStepUpProof(String stepUpProof) {
            this.stepUpProof = stepUpProof;
        }
    }

    record CreatedApiToken(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String token,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant expiresAt) {
    }

    record ApiTokenView(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant expiresAt,
            @Schema(description = "Null until the token is first used") Instant lastUsedAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean expired) {

        static ApiTokenView from(TokenSummary summary) {
            return new ApiTokenView(summary.id(), summary.name(), summary.createdAt(), summary.expiresAt(),
                    summary.lastUsedAt(), summary.expired());
        }
    }
}
