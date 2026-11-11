package com.flowcortex.user.authz;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.security.authz.RequireAnyPermission;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.user.api.AdminAssignee;
import com.flowcortex.user.api.AdminPermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/v1/admin/assignees?for=<key>} — the staff an admin may assign work to. */
@RestController
@RequestMapping("/api/v1/admin/assignees")
class AdminAssigneesController {

    private static final Logger LOG = LoggerFactory.getLogger(AdminAssigneesController.class);

    private final AdminPermissions permissions;

    AdminAssigneesController(AdminPermissions permissions) {
        this.permissions = permissions;
    }

    @Operation(operationId = "listAdminAssignees", responses = {
            @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    array = @ArraySchema(schema = @Schema(implementation = AdminAssignee.class)))),
            @ApiResponse(responseCode = "400", description = "Missing or unknown list (errors.for)",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "The caller may not read this list",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))})
    @RequireAnyPermission(Permission.SUPPORT_TICKET_ASSIGN)
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<List<AdminAssignee>> list(@AuthenticationPrincipal Jwt jwt,
            @Parameter(required = true, description = "The list to read, named by the permission it serves")
            @RequestParam(name = "for", required = false) String listKey) {
        Permission list = AssigneeLists.listFor(listKey).orElseThrow(() -> new InvalidAssigneeListException(
                new LocalizedMessage("user.assignees.error.for")));
        permissions.require(UUID.fromString(jwt.getSubject()), AssigneeLists.callerKeyFor(listKey).orElseThrow());
        List<AdminAssignee> holders = permissions.holders(list);
        LOG.info("admin.assignees list={} count={}", list.key(), holders.size());
        return ResponseEntity.ok(holders);
    }
}
