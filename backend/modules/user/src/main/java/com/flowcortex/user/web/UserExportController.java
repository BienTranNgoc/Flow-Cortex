package com.flowcortex.user.web;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.security.authz.RequirePermission;
import com.flowcortex.user.api.UserQuery;
import com.flowcortex.user.api.UserQuery.Sort;
import com.flowcortex.user.application.UserCsv;
import com.flowcortex.user.application.UserExportService;
import com.flowcortex.user.domain.AccountState;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/users/export} — the filtered users list as a CSV file. A separate class from the
 * JSON users controller so the route has its own permission posture and content types. The service
 * finishes before any header is set, so a refusal is an ordinary problem response.
 */
@RestController
@RequestMapping("/api/v1/users")
class UserExportController {

    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final UserExportService exportService;
    private final Clock clock;

    UserExportController(UserExportService exportService, Clock clock) {
        this.exportService = exportService;
        this.clock = clock;
    }

    @Operation(operationId = "exportUsers")
    @RequirePermission(Permission.USER_ACCOUNT_EXPORT)
    @GetMapping(value = "/export", produces = {"text/csv", MediaType.APPLICATION_PROBLEM_JSON_VALUE})
    ResponseEntity<byte[]> export(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,19}$") String role,
            @Parameter(schema = @Schema(allowableValues = {"active", "pending_confirmation", "locked",
                    "suspended"}))
            @RequestParam(required = false)
            @Pattern(regexp = "active|pending_confirmation|locked|suspended") String state,
            @Parameter(schema = @Schema(allowableValues = {"newest", "oldest", "email", "name"}))
            @RequestParam(defaultValue = "newest") @Pattern(regexp = "newest|oldest|email|name") String sort) {
        UserQuery query = new UserQuery(q, Optional.ofNullable(role),
                Optional.ofNullable(state).flatMap(AccountState::fromWire),
                Sort.valueOf(sort.toUpperCase(Locale.ROOT)));
        byte[] file = exportService.export(UUID.fromString(jwt.getSubject()), query);
        return ResponseEntity.ok()
                .contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(UserCsv.fileName(clock.instant())).build().toString())
                .body(file);
    }
}
