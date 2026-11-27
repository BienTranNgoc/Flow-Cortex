package com.flowcortex.entity.web;

import com.flowcortex.entity.application.EntityService;
import com.flowcortex.entity.application.EntityService.EntityQuery;
import com.flowcortex.entity.web.EntityCaller.Caller;
import com.flowcortex.entity.web.dto.CreateEntityRequest;
import com.flowcortex.entity.web.dto.EntityListResponse;
import com.flowcortex.entity.web.dto.EntityResponse;
import com.flowcortex.entity.web.dto.UpdateEntityRequest;
import com.flowcortex.space.api.SpaceAction;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/entities} — the entities of the caller's personal space. Identity and space come only from the
 * token; an entity of another space, a trashed one and an unknown or malformed id answer the same 404.
 */
@RestController
@RequestMapping("/api/v1/entities")
class EntitiesController {

    private static final int MAX_PAGE_SIZE = 100;
    /** Keeps {@code page * size} within an {@code int}, the offset type the list pages with. */
    private static final int MAX_PAGE = Integer.MAX_VALUE / MAX_PAGE_SIZE;

    private final EntityCaller entityCaller;
    private final EntityService service;

    EntitiesController(EntityCaller entityCaller, EntityService service) {
        this.entityCaller = entityCaller;
        this.service = service;
    }

    @Operation(operationId = "listEntities")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<EntityListResponse> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String category) {
        Caller caller = entityCaller.require(jwt, SpaceAction.ENTITY_READ);
        return ResponseEntity.ok(EntityListResponse.from(
                service.list(caller.spaceId(), new EntityQuery(kind, q, tag, category), page, size)));
    }

    @Operation(operationId = "createEntity")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<EntityResponse> create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateEntityRequest request) {
        Caller caller = entityCaller.require(jwt, SpaceAction.ENTITY_WRITE);
        EntityResponse created = EntityResponse.from(service.create(caller.userId(), caller.spaceId(), request.toInput()));
        return ResponseEntity.created(URI.create("/api/v1/entities/" + created.id())).body(created);
    }

    @Operation(operationId = "getEntity")
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<EntityResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        Caller caller = entityCaller.require(jwt, SpaceAction.ENTITY_READ);
        return ResponseEntity.ok(EntityResponse.from(service.get(caller.spaceId(), EntityIds.parse(id))));
    }

    @Operation(operationId = "updateEntity")
    @PatchMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<EntityResponse> update(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @Valid @RequestBody UpdateEntityRequest request) {
        Caller caller = entityCaller.require(jwt, SpaceAction.ENTITY_WRITE);
        return ResponseEntity.ok(EntityResponse.from(
                service.update(caller.spaceId(), EntityIds.parse(id), request.toPatch())));
    }

    @Operation(operationId = "deleteEntity")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        Caller caller = entityCaller.require(jwt, SpaceAction.ENTITY_WRITE);
        service.delete(caller.userId(), caller.spaceId(), EntityIds.parse(id));
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
