package com.flowcortex.entity.web;

import com.flowcortex.entity.application.EntityVersionService;
import com.flowcortex.entity.web.EntityCaller.Caller;
import com.flowcortex.entity.web.dto.EntityRestoreResponse;
import com.flowcortex.entity.web.dto.EntityVersionDetailResponse;
import com.flowcortex.entity.web.dto.EntityVersionResponse;
import com.flowcortex.entity.web.dto.RestoreEntityRequest;
import com.flowcortex.shared.web.PageView;
import com.flowcortex.space.api.SpaceAction;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/entities/{id}/versions} — the history of an entity of the caller's space and restore. Whatever hides
 * the entity (another space, trash, a malformed id) answers the same 404 as for the entity itself.
 */
@RestController
@RequestMapping("/api/v1/entities/{id}/versions")
class EntityVersionsController {

    private static final int MAX_PAGE_SIZE = 50;
    /** Keeps {@code page * size} within an {@code int}, the offset type the list pages with. */
    private static final int MAX_PAGE = Integer.MAX_VALUE / MAX_PAGE_SIZE;

    private final EntityCaller entityCaller;
    private final EntityVersionService service;

    EntityVersionsController(EntityCaller entityCaller, EntityVersionService service) {
        this.entityCaller = entityCaller;
        this.service = service;
    }

    @Operation(operationId = "listEntityVersions")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PageView<EntityVersionResponse>> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        Caller caller = entityCaller.require(jwt, SpaceAction.ENTITY_READ);
        return ResponseEntity.ok(service.list(caller.spaceId(), EntityIds.parse(id), page, size)
                .map(EntityVersionResponse::from));
    }

    @Operation(operationId = "getEntityVersion")
    @GetMapping(value = "/{n}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<EntityVersionDetailResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @PathVariable int n) {
        Caller caller = entityCaller.require(jwt, SpaceAction.ENTITY_READ);
        return ResponseEntity.ok(
                EntityVersionDetailResponse.from(service.read(caller.spaceId(), EntityIds.parse(id), n)));
    }

    @Operation(operationId = "restoreEntityVersion")
    @PostMapping(value = "/{n}/restore", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<EntityRestoreResponse> restore(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @PathVariable int n, @Valid @RequestBody RestoreEntityRequest request) {
        Caller caller = entityCaller.require(jwt, SpaceAction.ENTITY_WRITE);
        return ResponseEntity.ok(EntityRestoreResponse.from(
                service.restore(caller.spaceId(), EntityIds.parse(id), n, request.baseVersion())));
    }
}
