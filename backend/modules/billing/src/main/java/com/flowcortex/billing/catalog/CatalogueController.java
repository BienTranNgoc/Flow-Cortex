package com.flowcortex.billing.catalog;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What Nodi sells, readable before signing in. The answers never depend on the caller, so no principal is
 * read; the module's own security chain lets these two GETs through without a token.
 */
@RestController
@RequestMapping("/api/v1/billing")
class CatalogueController {

    private final CatalogueReader reader;
    private final CatalogueTelemetry telemetry;

    CatalogueController(CatalogueReader reader, CatalogueTelemetry telemetry) {
        this.reader = reader;
        this.telemetry = telemetry;
    }

    @Operation(operationId = "listBillingPlans", summary = "List the plans on sale",
            description = "Anyone may read it. Tiers by position, each with the cycles it is sold on.")
    @ApiResponse(responseCode = "200", description = "The tiers on sale")
    @SecurityRequirements
    @GetMapping(path = "/plans", produces = MediaType.APPLICATION_JSON_VALUE)
    PlanListResponse plans() {
        return PlanListResponse.from(telemetry.observedRead("plans", reader::readPlans));
    }

    @Operation(operationId = "listTopupPackages", summary = "List the top-up packages on sale",
            description = "Anyone may read it. Packages by position, then price.")
    @ApiResponse(responseCode = "200", description = "The top-up packages on sale")
    @SecurityRequirements
    @GetMapping(path = "/topup-packages", produces = MediaType.APPLICATION_JSON_VALUE)
    TopupPackageListResponse topupPackages() {
        return TopupPackageListResponse.from(telemetry.observedRead("topup_packages", reader::readTopupPackages));
    }
}
