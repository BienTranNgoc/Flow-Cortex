package com.flowcortex.user.web;

import com.flowcortex.user.api.OwnProfile;
import com.flowcortex.user.api.OwnProfiles;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/auth/profile}: the caller's own names and username; the names write is {@code PUT} on the same path in {@link AuthController}. */
@RestController
@RequestMapping("/api/v1/auth/profile")
class OwnProfileController {

    private final OwnProfiles profiles;

    OwnProfileController(OwnProfiles profiles) {
        this.profiles = profiles;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    OwnProfileResponse get(@AuthenticationPrincipal Jwt jwt) {
        return OwnProfileResponse.from(profiles.get(subjectId(jwt)));
    }

    @PutMapping(value = "/username", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    OwnProfileResponse changeUsername(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UsernameRequest request) {
        return OwnProfileResponse.from(profiles.changeUsername(subjectId(jwt), request.username()));
    }

    private static UUID subjectId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    record UsernameRequest(
            @NotNull @Pattern(regexp = "^[a-z0-9_]{3,20}$", message = "Use 3–20 lowercase letters, digits or _")
            String username) {
    }

    /** {@code usernameNextChangeAt} is absent while the username may change now. */
    record OwnProfileResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            String firstName,
            String lastName,
            String username,
            Instant usernameNextChangeAt) {

        static OwnProfileResponse from(OwnProfile profile) {
            return new OwnProfileResponse(profile.name(), profile.firstName(), profile.lastName(),
                    profile.username(), profile.usernameNextChangeAt());
        }
    }
}
