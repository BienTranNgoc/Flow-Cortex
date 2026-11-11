package com.flowcortex.user.accounts;

import com.flowcortex.shared.security.authz.Permission;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * A role with the keys it holds, sorted by key; a super admin lists every key of the catalog.
 * {@code permissionsVersion} is the value a key replacement must echo back.
 */
record RoleResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true) String description,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean system,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean staff,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean superAdmin,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Permission> permissions,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int permissionsVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int accountCount) {
}
