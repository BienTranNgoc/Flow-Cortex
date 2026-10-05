package com.flowcortex.user.web;

import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/users} — ADMIN-only CRUD scoped to the caller's own owner scope. A record
 * outside that scope is a 404, not a 403 — {@link UserService} enforces that; this controller
 * just relays what it throws.
 *
 * <p>Role/status are constrained by Jakarta Bean Validation, so an invalid value fails through
 * the same {@code MethodArgumentNotValidException} -> 400-problem path as any other validation.
 */
@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize("hasRole('ADMIN')")
class UsersController {

    private final UserService userService;

    UsersController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<List<UserResponse>> list(@AuthenticationPrincipal Jwt jwt) {
        List<UserResponse> users = userService.listUsers(ownerId(jwt)).stream()
                .map(UserResponse::from)
                .toList();
        return ResponseEntity.ok(users);
    }

    // @ResponseStatus documents 201 for springdoc/orval — Spring MVC ignores it for a
    // ResponseEntity-returning method (actual 201 comes from ResponseEntity.status() below).
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<UserResponse> create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateUserRequest request) {
        UserView created = userService.createUser(ownerId(jwt), request.email(), request.password(),
                request.name(), request.role());
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(created));
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<UserResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok(UserResponse.from(userService.getUser(ownerId(jwt), id)));
    }

    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<UserResponse> update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody UpdateUserRequest request) {
        UserView updated = userService.updateUser(ownerId(jwt), id, request.email(), request.name(),
                request.role(), request.status());
        return ResponseEntity.ok(UserResponse.from(updated));
    }

    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        userService.deleteUser(ownerId(jwt), id);
        return ResponseEntity.noContent().build();
    }

    private static UUID ownerId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    record CreateUserRequest(
            @NotBlank @Email String email,
            // max = 72 characters only publishes a length bound in the OpenAPI schema; BCrypt's
            // real limit is bytes, enforced by @MaxUtf8Bytes.
            @NotBlank @Size(min = 8, max = 72) @MaxUtf8Bytes(72) String password,
            @NotBlank String name,
            @NotBlank @Pattern(regexp = "ADMIN|MEMBER|CREATOR") String role) {
    }

    /** Partial update — a {@code null} field is left unchanged. */
    record UpdateUserRequest(
            @Email String email,
            String name,
            @Pattern(regexp = "ADMIN|MEMBER|CREATOR") String role,
            @Pattern(regexp = "ACTIVE|SUSPENDED") String status) {
    }

    /** Never carries {@code passwordHash} — {@link UserView} does not either. */
    record UserResponse(UUID id, String email, String name, String role, String status, Instant createdAt,
            Instant updatedAt) {
        static UserResponse from(UserView view) {
            return new UserResponse(view.id(), view.email(), view.name(), view.role(), view.status(),
                    view.createdAt(), view.updatedAt());
        }
    }
}
