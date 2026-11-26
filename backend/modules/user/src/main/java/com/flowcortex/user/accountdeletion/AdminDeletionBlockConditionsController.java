package com.flowcortex.user.accountdeletion;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.security.authz.RequirePermission;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.audit.AdminAudit;
import com.flowcortex.user.accountdeletion.DeletionBlockConditionStore.Condition;
import com.flowcortex.user.accountdeletion.DeletionBlockConditionStore.Defaults;
import com.flowcortex.user.api.UserDirectory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/admin/account-deletion/block-conditions}: the registered blocking conditions an operator
 * can switch, order and reword. Reads need {@code platform.setting.view}, a write {@code platform.setting.update}.
 */
@RestController
@RequestMapping("/api/v1/admin/account-deletion/block-conditions")
class AdminDeletionBlockConditionsController {

    static final int SORT_ORDER_MAX = 999;
    static final int MESSAGE_MAX = 500;

    private final DeletionBlockerRegistry registry;
    private final DeletionBlockConditionStore store;
    private final UserDirectory users;
    private final Clock clock;

    AdminDeletionBlockConditionsController(DeletionBlockerRegistry registry, DeletionBlockConditionStore store,
            UserDirectory users, Clock clock) {
        this.registry = registry;
        this.store = store;
        this.users = users;
        this.clock = clock;
    }

    /** Messages are trimmed on the way in, so the length and blank checks judge what is stored. */
    @Schema(name = "DeletionBlockConditionUpdate")
    record ConditionUpdate(
            @NotNull Boolean enabled,
            @NotNull @Min(0) @Max(SORT_ORDER_MAX) Integer sortOrder,
            @NotBlank @Size(max = MESSAGE_MAX) String messageVi,
            @NotBlank @Size(max = MESSAGE_MAX) String messageEn) {

        ConditionUpdate {
            messageVi = messageVi == null ? null : messageVi.strip();
            messageEn = messageEn == null ? null : messageEn.strip();
        }
    }

    /** {@code updatedBy} is the last editor's email; null when nobody edited the row or that account is gone. */
    @Schema(name = "DeletionBlockConditionView")
    record ConditionView(
            @Schema(requiredMode = RequiredMode.REQUIRED) String key,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean enabled,
            @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "0", maximum = "999") int sortOrder,
            @Schema(requiredMode = RequiredMode.REQUIRED) String messageVi,
            @Schema(requiredMode = RequiredMode.REQUIRED) String messageEn,
            String updatedBy,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant updatedAt) {
    }

    @Operation(operationId = "listAdminDeletionBlockConditions")
    @RequirePermission(Permission.PLATFORM_SETTING_VIEW)
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    List<ConditionView> list() {
        seedMissing();
        return views(store.findAll().stream().filter(row -> registry.find(row.key()).isPresent()).toList());
    }

    @Operation(operationId = "updateAdminDeletionBlockCondition")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "validation-failed",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "The key is not a registered condition",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.PLATFORM_SETTING_UPDATE)
    @AdminAudit(value = "deletion_block_condition.update", resourceParam = "key",
            capture = {"enabled", "sortOrder"})
    @PutMapping(path = "/{key}", produces = MediaType.APPLICATION_JSON_VALUE)
    ConditionView update(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
            @Valid @RequestBody ConditionUpdate body) {
        if (registry.find(key).isEmpty()) {
            throw new NotFoundException(new LocalizedMessage("account.deletion.conditionNotFound", key));
        }
        seedMissing();
        int changed = store.update(key, body.enabled(), body.sortOrder(), body.messageVi(), body.messageEn(),
                UUID.fromString(jwt.getSubject()), clock.instant());
        if (changed == 0) {
            throw new NotFoundException(new LocalizedMessage("account.deletion.conditionNotFound", key));
        }
        return views(store.findAll().stream().filter(row -> row.key().equals(key)).toList()).get(0);
    }

    /** A condition no member has been evaluated against yet has no row; the console must still show and edit it. */
    private void seedMissing() {
        store.insertMissing(registry.all().stream()
                .map(blocker -> new Defaults(blocker.key(), blocker.defaultSortOrder(),
                        blocker.defaultMessageVi(), blocker.defaultMessageEn()))
                .toList());
    }

    private List<ConditionView> views(List<Condition> rows) {
        Set<UUID> actors = rows.stream().map(Condition::updatedBy).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> emails = actors.isEmpty() ? Map.of() : users.findEmailsByIds(actors);
        return rows.stream()
                .map(row -> new ConditionView(row.key(), row.enabled(), row.sortOrder(), row.messageVi(),
                        row.messageEn(), row.updatedBy() == null ? null : emails.get(row.updatedBy()),
                        row.updatedAt()))
                .toList();
    }
}
