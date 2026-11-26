package com.flowcortex.workflow.web;

import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.workflow.application.MarketplaceQuery;
import com.flowcortex.workflow.application.MarketplaceQuery.Browse;
import com.flowcortex.workflow.application.MarketplaceService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Discovery reads over the catalogue of published public workflows, and the caller's own counts. A refused
 * parameter is a 400 naming it, answered by Spring's handler-method validation before the service runs.
 * {@code /workflows/counts} is a literal segment, so it wins over {@code /workflows/{id}}.
 */
@RestController
@RequestMapping("/api/v1")
class MarketplaceController {

    private static final int MAX_PAGE_SIZE = 50;
    /** Keeps {@code page * size} within an {@code int}, the offset type of the page query. */
    private static final int MAX_PAGE = Integer.MAX_VALUE / MAX_PAGE_SIZE;
    private static final int MAX_LIMIT = 20;

    private final MarketplaceService marketplace;
    private final CurrentUserResolver currentUserResolver;
    private final CreditAccounts creditAccounts;

    MarketplaceController(MarketplaceService marketplace, CurrentUserResolver currentUserResolver,
            CreditAccounts creditAccounts) {
        this.marketplace = marketplace;
        this.currentUserResolver = currentUserResolver;
        this.creditAccounts = creditAccounts;
    }

    @Operation(operationId = "listMarketplaceWorkflows")
    @GetMapping(value = "/marketplace/workflows", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<MarketplaceResponses.Page> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @MarketplaceSearch String q,
            @RequestParam(required = false) @MarketplaceStepTypes List<String> stepType,
            @RequestParam(required = false) @MarketplaceSort String sort,
            @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        UUID callerId = currentUserResolver.require(jwt);
        Browse browse = MarketplaceQuery.browse(q, stepType, MarketplaceQuery.Sort.parse(sort).orElseThrow(), page,
                size);
        return ResponseEntity.ok(MarketplaceResponses.Page.from(marketplace.browse(callerId, browse), page, size,
                creditAccounts));
    }

    @Operation(operationId = "listRecommendedWorkflows")
    @GetMapping(value = "/marketplace/recommended", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<MarketplaceResponses.Items> recommended(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "10") @Min(1) @Max(MAX_LIMIT) int limit) {
        UUID callerId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(new MarketplaceResponses.Items(
                MarketplaceResponses.Item.from(marketplace.recommended(callerId, limit), creditAccounts)));
    }

    @Operation(operationId = "listSimilarWorkflows")
    @GetMapping(value = "/marketplace/workflows/{id}/similar", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<MarketplaceResponses.Items> similar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestParam(defaultValue = "8") @Min(1) @Max(MAX_LIMIT) int limit) {
        UUID callerId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(new MarketplaceResponses.Items(
                MarketplaceResponses.Item.from(marketplace.similar(callerId, id, limit), creditAccounts)));
    }

    @Operation(operationId = "getMarketplaceFacets")
    @GetMapping(value = "/marketplace/facets", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<MarketplaceResponses.Facets> facets(@AuthenticationPrincipal Jwt jwt) {
        currentUserResolver.require(jwt);
        return ResponseEntity.ok(MarketplaceResponses.Facets.from(marketplace.facets()));
    }

    @Operation(operationId = "getMarketplaceStats")
    @GetMapping(value = "/marketplace/stats", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<MarketplaceResponses.Stats> stats(@AuthenticationPrincipal Jwt jwt) {
        currentUserResolver.require(jwt);
        return ResponseEntity.ok(MarketplaceResponses.Stats.from(marketplace.stats()));
    }

    @Operation(operationId = "getWorkflowCounts")
    @GetMapping(value = "/workflows/counts", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<MarketplaceResponses.Counts> counts(@AuthenticationPrincipal Jwt jwt) {
        UUID callerId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(MarketplaceResponses.Counts.from(marketplace.counts(callerId)));
    }
}
