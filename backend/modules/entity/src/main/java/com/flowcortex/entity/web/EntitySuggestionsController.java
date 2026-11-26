package com.flowcortex.entity.web;

import com.flowcortex.entity.application.EntitySuggestionService;
import com.flowcortex.entity.web.EntityCaller.Caller;
import com.flowcortex.entity.web.dto.EntitySuggestionResponse;
import com.flowcortex.entity.web.dto.EntityTagSuggestionResponse;
import com.flowcortex.space.api.SpaceAction;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Tags, categories and part labels the caller's live entities already use. */
@RestController
@RequestMapping("/api/v1/entities")
class EntitySuggestionsController {

    private static final int MAX_LIMIT = 20;
    private static final int MAX_CATEGORY_LIMIT = 50;

    private final EntityCaller entityCaller;
    private final EntitySuggestionService service;

    EntitySuggestionsController(EntityCaller entityCaller, EntitySuggestionService service) {
        this.entityCaller = entityCaller;
        this.service = service;
    }

    @Operation(operationId = "listEntityTags")
    @GetMapping(value = "/tags", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<EntityTagSuggestionResponse> tags(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "10") @Min(1) @Max(MAX_LIMIT) int limit) {
        Caller caller = entityCaller.require(jwt, SpaceAction.ENTITY_READ);
        return ResponseEntity.ok(EntityTagSuggestionResponse.from(service.tags(caller.spaceId(), q, limit)));
    }

    @Operation(operationId = "listEntityCategories")
    @GetMapping(value = "/categories", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<EntitySuggestionResponse> categories(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "50") @Min(1) @Max(MAX_CATEGORY_LIMIT) int limit) {
        Caller caller = entityCaller.require(jwt, SpaceAction.ENTITY_READ);
        return ResponseEntity.ok(EntitySuggestionResponse.from(service.categories(caller.spaceId(), q, limit)));
    }

    @Operation(operationId = "listEntityLabels")
    @GetMapping(value = "/labels", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<EntitySuggestionResponse> labels(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "8") @Min(1) @Max(MAX_LIMIT) int limit) {
        Caller caller = entityCaller.require(jwt, SpaceAction.ENTITY_READ);
        return ResponseEntity.ok(EntitySuggestionResponse.from(service.labels(caller.spaceId(), role, q, limit)));
    }
}
