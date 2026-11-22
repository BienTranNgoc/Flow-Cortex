package com.flowcortex.user.accounts;

import com.flowcortex.shared.security.authz.Permission;
import io.swagger.v3.oas.annotations.media.Schema;

/** One key of the permission catalog, split into the parts the matrix groups by. */
record PermissionCatalogEntry(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Permission key,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String module,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String feature,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String action,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean superAdminOnly) {

    static PermissionCatalogEntry of(Permission permission) {
        return new PermissionCatalogEntry(permission, permission.module(), permission.feature(),
                permission.action(), permission.superAdminOnly());
    }
}
