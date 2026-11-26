package com.flowcortex.entity.web;

import com.flowcortex.entity.application.EntityRunHistory;
import com.flowcortex.entity.web.EntityCaller.Caller;
import com.flowcortex.entity.web.dto.EntityRunResponse;
import com.flowcortex.shared.web.PageView;
import com.flowcortex.space.api.SpaceAction;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/entities/{id}/runs} — the completed runs that used one entity of the caller's space. */
@RestController
@RequestMapping("/api/v1/entities")
class EntityRunsController {

    private static final int MAX_PAGE_SIZE = 50;
    /** Keeps {@code page * size} within an {@code int}, the offset type the page is read with. */
    private static final int MAX_PAGE = Integer.MAX_VALUE / MAX_PAGE_SIZE;

    private final EntityCaller entityCaller;
    private final EntityRunHistory history;

    EntityRunsController(EntityCaller entityCaller, EntityRunHistory history) {
        this.entityCaller = entityCaller;
        this.history = history;
    }

    @Operation(operationId = "listEntityRuns")
    @GetMapping(value = "/{id}/runs", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PageView<EntityRunResponse>> runs(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        Caller caller = entityCaller.require(jwt, SpaceAction.ENTITY_READ);
        return ResponseEntity.ok(history.completedRuns(caller.spaceId(), EntityIds.parse(id), page, size)
                .map(EntityRunResponse::from));
    }
}
