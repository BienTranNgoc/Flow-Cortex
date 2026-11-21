package com.flowcortex.user.accounts;

import com.flowcortex.user.domain.AppUser;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** An account whose role is staff or {@code ADMIN}, as the admin accounts API returns it. */
record AdminAccountResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String email,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String role,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean adminDisabled,
        Instant adminDisabledAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt) {

    static AdminAccountResponse from(AppUser user) {
        return new AdminAccountResponse(user.getId(), user.getEmail(), user.getName(), user.getRole().name(),
                user.getStatus().name(), user.isAdminDisabled(), user.getAdminDisabledAt(), user.getCreatedAt());
    }
}
