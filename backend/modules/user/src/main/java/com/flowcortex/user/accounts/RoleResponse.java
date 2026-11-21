package com.flowcortex.user.accounts;

import com.flowcortex.shared.security.authz.Permission;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** A role with the keys it holds, sorted by key; a super admin lists every key of the catalog. */
record RoleResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean staff,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean superAdmin,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Permission> permissions) {
}
