package com.flowcortex.user.accounts;

import com.flowcortex.user.domain.AppUser;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** An account whose role is staff or {@code ADMIN}, as the admin accounts API returns it. */
record AdminAccountResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String email,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String role,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String roleName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean adminDisabled,
        Instant adminDisabledAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt) {

    /** {@code roleNames} is the role catalog's code-to-name map; a code missing from it shows as itself. */
    static AdminAccountResponse from(AppUser user, Map<String, String> roleNames) {
        String code = user.getRole().code();
        return new AdminAccountResponse(user.getId(), user.getEmail(), user.getName(), code,
                roleNames.getOrDefault(code, code), user.getStatus().name(), user.isAdminDisabled(),
                user.getAdminDisabledAt(), user.getCreatedAt());
    }
}
