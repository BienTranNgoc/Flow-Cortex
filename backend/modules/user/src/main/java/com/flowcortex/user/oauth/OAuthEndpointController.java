package com.flowcortex.user.oauth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.flowcortex.shared.ratelimit.RateLimiter;
import com.flowcortex.shared.ratelimit.RateLimiters;
import com.flowcortex.user.oauth.ClientCredentials.Credentials;
import com.flowcortex.user.oauth.ClientRegistrationRules.Invalid;
import com.flowcortex.user.oauth.ClientRegistrationRules.Outcome;
import com.flowcortex.user.oauth.ClientRegistrationRules.Valid;
import com.flowcortex.user.oauth.McpOAuthService.ExchangeRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The machine-facing routes of the MCP authorization server: metadata, dynamic client registration,
 * code exchange and revocation. They answer OAuth JSON ({@link OAuthErrorAdvice}) and read their own
 * input, so the per-address limit runs before anything is parsed or looked up.
 */
@RestController
class OAuthEndpointController {

    private static final int MAX_REGISTRATION_BYTES = 64 * 1024;
    private static final String AUTHORIZATION_CODE = "authorization_code";
    private static final String BASIC = "Basic ";
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    private final McpOAuthProperties properties;
    private final RateLimiter registerLimiter;
    private final RateLimiter tokenLimiter;
    private final OAuthClientService clients;
    private final McpOAuthService service;

    OAuthEndpointController(McpOAuthProperties properties, RateLimiters rateLimiters, OAuthClientService clients,
            McpOAuthService service) {
        this.properties = properties;
        this.registerLimiter = rateLimiters.named(OAuthRateLimits.REGISTER_IP);
        this.tokenLimiter = rateLimiters.named(OAuthRateLimits.TOKEN_IP);
        this.clients = clients;
        this.service = service;
    }

    record Metadata(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String issuer,
            @JsonProperty("authorization_endpoint") @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String authorizationEndpoint,
            @JsonProperty("token_endpoint") @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String tokenEndpoint,
            @JsonProperty("registration_endpoint") @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String registrationEndpoint,
            @JsonProperty("revocation_endpoint") @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String revocationEndpoint,
            @JsonProperty("response_types_supported") @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            List<String> responseTypesSupported,
            @JsonProperty("grant_types_supported") @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            List<String> grantTypesSupported,
            @JsonProperty("code_challenge_methods_supported") @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            List<String> codeChallengeMethodsSupported,
            @JsonProperty("scopes_supported") @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            List<String> scopesSupported,
            @JsonProperty("token_endpoint_auth_methods_supported") @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            List<String> tokenEndpointAuthMethodsSupported,
            @JsonProperty("revocation_endpoint_auth_methods_supported")
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> revocationEndpointAuthMethodsSupported) {
    }

    record RegistrationRequest(
            @JsonProperty("client_name") String clientName,
            @JsonProperty("redirect_uris") List<String> redirectUris,
            @JsonProperty("grant_types") List<String> grantTypes,
            @JsonProperty("response_types") List<String> responseTypes,
            @JsonProperty("token_endpoint_auth_method") String tokenEndpointAuthMethod) {
    }

    record Registration(
            @JsonProperty("client_id") @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String clientId,
            @JsonProperty("client_secret") @Schema(description = "Absent for a client that authenticates with none")
            String clientSecret,
            @JsonProperty("client_id_issued_at") @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long clientIdIssuedAt,
            @JsonProperty("client_secret_expires_at") @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long clientSecretExpiresAt,
            @JsonProperty("client_name") @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String clientName,
            @JsonProperty("redirect_uris") @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            List<String> redirectUris,
            @JsonProperty("grant_types") @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            List<String> grantTypes,
            @JsonProperty("response_types") @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            List<String> responseTypes,
            @JsonProperty("token_endpoint_auth_method") @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String tokenEndpointAuthMethod) {
    }

    record TokenResponse(
            @JsonProperty("access_token") @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String accessToken,
            @JsonProperty("token_type") @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String tokenType,
            @JsonProperty("expires_in") @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long expiresIn,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String scope) {
    }

    /** Documents the form of the token route; the controller reads the fields itself. */
    record TokenForm(
            @JsonProperty("grant_type") @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String grantType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
            @JsonProperty("code_verifier") @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String codeVerifier,
            @JsonProperty("redirect_uri") @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String redirectUri,
            @JsonProperty("client_id") String clientId,
            @JsonProperty("client_secret") String clientSecret) {
    }

    /** Documents the form of the revocation route; the controller reads the fields itself. */
    record RevokeForm(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String token,
            @JsonProperty("token_type_hint") String tokenTypeHint,
            @JsonProperty("client_id") String clientId,
            @JsonProperty("client_secret") String clientSecret) {
    }

    @Operation(operationId = "oauthMetadata")
    @SecurityRequirements
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "503", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @GetMapping(path = McpOAuthSwitch.METADATA_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    Metadata metadata() {
        String issuer = properties.issuer();
        String api = issuer + "/api/v1/oauth/";
        List<String> authMethods = List.of(OAuthClient.AUTH_POST, OAuthClient.AUTH_BASIC, OAuthClient.AUTH_NONE);
        return new Metadata(issuer, issuer + "/oauth/authorize", api + "token", api + "register", api + "revoke",
                List.of("code"), List.of(AUTHORIZATION_CODE), List.of("S256"), List.of(AuthorizationRequestCheck.SCOPE),
                authMethods, authMethods);
    }

    @Operation(operationId = "registerOAuthClient")
    @SecurityRequirements
    @ApiResponse(responseCode = "201")
    @ApiResponse(responseCode = "400", description = "invalid_redirect_uri or invalid_client_metadata as OAuth JSON",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = OAuthErrorAdvice.ErrorBody.class)))
    @ApiResponse(responseCode = "429", description = "temporarily_unavailable as OAuth JSON, with Retry-After",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = OAuthErrorAdvice.ErrorBody.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @RequestBody(required = true, content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = RegistrationRequest.class)))
    @PostMapping(path = "/api/v1/oauth/register", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Registration> register(HttpServletRequest request) {
        throttle(registerLimiter, request);
        Outcome outcome = ClientRegistrationRules.evaluate(toRules(readRegistration(request)));
        if (outcome instanceof Invalid invalid) {
            throw OAuthException.of(invalid.error(), invalid.description());
        }
        OAuthClientService.Registered registered = clients.register((Valid) outcome);
        OAuthClient client = registered.client();
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(new Registration(client.clientId(), registered.secret(), client.createdAt().getEpochSecond(), 0,
                        client.clientName(), client.redirectUris(), List.of(AUTHORIZATION_CODE), List.of("code"),
                        client.authMethod()));
    }

    @Operation(operationId = "oauthToken")
    @SecurityRequirements
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400",
            description = "OAuth JSON: invalid_request, invalid_grant or unsupported_grant_type",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = OAuthErrorAdvice.ErrorBody.class)))
    @ApiResponse(responseCode = "401", description = "OAuth JSON: invalid_client",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = OAuthErrorAdvice.ErrorBody.class)))
    @ApiResponse(responseCode = "429", description = "temporarily_unavailable as OAuth JSON, with Retry-After",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = OAuthErrorAdvice.ErrorBody.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @RequestBody(required = true, content = @Content(mediaType = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            schema = @Schema(implementation = TokenForm.class)))
    @PostMapping(path = "/api/v1/oauth/token", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<TokenResponse> token(HttpServletRequest request) {
        throttle(tokenLimiter, request);
        requireForm(request);
        String grantType = single(request, "grant_type");
        if (grantType == null) {
            throw OAuthException.of("invalid_request", "grant_type is required");
        }
        if (!AUTHORIZATION_CODE.equals(grantType)) {
            throw OAuthException.of("unsupported_grant_type", "Only the authorization_code grant is supported");
        }
        OAuthClient client = authenticate(request);
        ExchangeRequest exchange = new ExchangeRequest(required(request, "code"), required(request, "code_verifier"),
                required(request, "redirect_uri"));
        return switch (service.exchange(client, exchange, request.getRemoteAddr())) {
            case ExchangeResult.Granted granted -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .body(new TokenResponse(granted.accessToken(), "Bearer", granted.expiresIn(), granted.scope()));
            case ExchangeResult.Denied denied -> throw OAuthException.of(denied.error(),
                    "The authorization code is invalid, expired, already used or does not match this request");
        };
    }

    @Operation(operationId = "revokeOAuthToken")
    @SecurityRequirements
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "OAuth JSON: invalid_request",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = OAuthErrorAdvice.ErrorBody.class)))
    @ApiResponse(responseCode = "401", description = "OAuth JSON: invalid_client",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = OAuthErrorAdvice.ErrorBody.class)))
    @ApiResponse(responseCode = "429", description = "temporarily_unavailable as OAuth JSON, with Retry-After",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = OAuthErrorAdvice.ErrorBody.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @RequestBody(required = true, content = @Content(mediaType = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            schema = @Schema(implementation = RevokeForm.class)))
    @PostMapping(path = "/api/v1/oauth/revoke", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> revoke(HttpServletRequest request) {
        throttle(tokenLimiter, request);
        requireForm(request);
        OAuthClient client = authenticate(request);
        service.revoke(client, required(request, "token"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache")
                .body(Map.of());
    }

    private static void throttle(RateLimiter limiter, HttpServletRequest request) {
        Optional<Duration> wait = limiter.tryAcquire(request.getRemoteAddr());
        if (wait.isPresent()) {
            throw OAuthException.throttled((wait.get().toNanos() + 999_999_999L) / 1_000_000_000L);
        }
    }

    private static void requireForm(HttpServletRequest request) {
        if (!hasMediaType(request, MediaType.APPLICATION_FORM_URLENCODED)) {
            throw OAuthException.of("invalid_request", "The request must be application/x-www-form-urlencoded");
        }
    }

    private static boolean hasMediaType(HttpServletRequest request, MediaType expected) {
        String contentType = request.getContentType();
        if (contentType == null) {
            return false;
        }
        try {
            return expected.isCompatibleWith(MediaType.parseMediaType(contentType));
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }

    private OAuthClient authenticate(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        boolean attemptedBasic = header != null && header.regionMatches(true, 0, BASIC, 0, BASIC.length());
        Credentials credentials = ClientCredentials
                .from(header, single(request, "client_id"), single(request, "client_secret"))
                .orElseThrow(() -> OAuthException.invalidClient(attemptedBasic));
        return clients.authenticate(credentials).orElseThrow(() -> OAuthException.invalidClient(credentials.viaBasic()));
    }

    /** The one value of a form field; a repeated field is refused, as RFC 6749 section 3.2 requires. */
    private static String single(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values == null || values.length == 0) {
            return null;
        }
        if (values.length > 1) {
            throw OAuthException.of("invalid_request", "A parameter is repeated");
        }
        return values[0].isBlank() ? null : values[0];
    }

    private static String required(HttpServletRequest request, String name) {
        String value = single(request, name);
        if (value == null) {
            throw OAuthException.of("invalid_request", name + " is required");
        }
        return value;
    }

    private static ClientRegistrationRules.Request toRules(RegistrationRequest body) {
        return new ClientRegistrationRules.Request(body.clientName(), body.redirectUris(), body.grantTypes(),
                body.responseTypes(), body.tokenEndpointAuthMethod());
    }

    private static RegistrationRequest readRegistration(HttpServletRequest request) {
        if (!hasMediaType(request, MediaType.APPLICATION_JSON)) {
            throw OAuthException.of(ClientRegistrationRules.INVALID_CLIENT_METADATA,
                    "The request must be application/json");
        }
        try {
            byte[] body = request.getInputStream().readNBytes(MAX_REGISTRATION_BYTES + 1);
            if (body.length == 0 || body.length > MAX_REGISTRATION_BYTES) {
                throw OAuthException.of(ClientRegistrationRules.INVALID_CLIENT_METADATA,
                        "The registration body is empty or larger than " + MAX_REGISTRATION_BYTES + " bytes");
            }
            RegistrationRequest parsed = JSON.readValue(body, RegistrationRequest.class);
            if (parsed == null) {
                throw OAuthException.of(ClientRegistrationRules.INVALID_CLIENT_METADATA,
                        "The registration body must be a JSON object");
            }
            return parsed;
        } catch (JacksonException e) {
            throw OAuthException.of(ClientRegistrationRules.INVALID_CLIENT_METADATA,
                    "The registration body is not valid client metadata");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
