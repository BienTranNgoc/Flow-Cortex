package com.flowcortex.billing.entitlement;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.security.Principal;
import java.time.Clock;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own plan. The member is the token's subject and no parameter names another one. */
@RestController
@RequestMapping("/api/v1/billing/plan")
class MemberPlanController {

    private final MemberPlanReader reader;
    private final Clock clock;

    MemberPlanController(MemberPlanReader reader, Clock clock) {
        this.reader = reader;
        this.clock = clock;
    }

    @Operation(operationId = "getMyBillingPlan", summary = "Read the plan the member holds",
            description = "Free with no period when no paid plan is held; nextPlan is the plan recorded for the next "
                    + "cycle until its period begins.")
    @ApiResponse(responseCode = "200", description = "The plan held now")
    @ApiResponse(responseCode = "401", description = "Not signed in", content = @Content)
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    MemberPlanResponse get(Principal principal) {
        return reader.read(UUID.fromString(principal.getName()), clock.instant());
    }
}
