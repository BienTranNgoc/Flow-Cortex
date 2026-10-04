package com.flowcortex.user.web;

import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.AuthService;
import com.flowcortex.user.api.CurrentUser;
import com.flowcortex.user.api.UserDirectory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/auth}: login, the caller's own profile ({@code me}), and self-service name
 * update ({@code profile} — email/role/status are admin-mediated only).
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private final AuthService authService;
    private final UserDirectory userDirectory;

    AuthController(AuthService authService, UserDirectory userDirectory) {
        this.authService = authService;
        this.userDirectory = userDirectory;
    }

    @PostMapping(value = "/login", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        String token = authService.login(request.email(), request.password());
        return ResponseEntity.ok(new LoginResponse(token));
    }

    @PostMapping(value = "/register", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        String token = authService.register(request.email(), request.password(), request.name());
        return ResponseEntity.status(201).body(new LoginResponse(token));
    }

    @GetMapping(value = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<MeResponse> me(@AuthenticationPrincipal Jwt jwt) {
        UUID id = subjectId(jwt);
        CurrentUser user = userDirectory.findById(id)
                .orElseThrow(() -> new NotFoundException("User not found: " + id));
        return ResponseEntity.ok(MeResponse.from(user));
    }

    @PutMapping(value = "/profile", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<MeResponse> updateProfile(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ProfileUpdateRequest request) {
        CurrentUser user = authService.updateOwnProfile(subjectId(jwt), request.name());
        return ResponseEntity.ok(MeResponse.from(user));
    }

    private static UUID subjectId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    record RegisterRequest(@NotBlank @jakarta.validation.constraints.Email String email,
            @NotBlank @jakarta.validation.constraints.Size(min = 8, max = 72) String password,
            @NotBlank String name) {
    }

    record LoginResponse(String token) {
    }

    record ProfileUpdateRequest(@NotBlank String name) {
    }

    /** Never carries {@code passwordHash} — {@link CurrentUser} does not either. */
    record MeResponse(UUID id, String email, String name, String role, String status) {
        static MeResponse from(CurrentUser user) {
            return new MeResponse(user.id(), user.email(), user.name(), user.role(), user.status());
        }
    }
}
