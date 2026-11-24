package com.flowcortex.user.oauth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Answers the token, registration and revocation routes' errors as OAuth JSON, never as
 * problem+json, and never with a request value in the body. Scoped to {@link OAuthEndpointController}
 * so the consent routes keep the member app's problem contract.
 */
@RestControllerAdvice(assignableTypes = OAuthEndpointController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class OAuthErrorAdvice {

    @Schema(name = "OAuthError", description = "The error object of RFC 6749 section 5.2, also used by RFC 7591")
    record ErrorBody(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The OAuth error code, for example"
                    + " invalid_grant") String error,
            @JsonProperty("error_description") @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "A human-readable explanation; never echoes a request value")
            String errorDescription) {
    }

    @ExceptionHandler(OAuthException.class)
    ResponseEntity<ErrorBody> handle(OAuthException ex) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.status())
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache");
        if (ex.basicChallenge()) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"oauth\"");
        }
        if (ex.retryAfterSeconds() > 0) {
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds()));
        }
        return response.body(new ErrorBody(ex.error(), ex.getMessage()));
    }
}
