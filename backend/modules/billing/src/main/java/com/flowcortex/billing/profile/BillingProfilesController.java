package com.flowcortex.billing.profile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * A member's own invoice details. The caller is the token's subject and every statement is filtered by it, so
 * a profile of another member, an unknown one and a malformed identifier all answer the same 404.
 */
@RestController
@RequestMapping("/api/v1/billing/profiles")
class BillingProfilesController {

    private final BillingProfiles profiles;

    BillingProfilesController(BillingProfiles profiles) {
        this.profiles = profiles;
    }

    @Operation(operationId = "listBillingProfiles", summary = "List the member's invoice details",
            description = "The default first, then oldest first.")
    @ApiResponse(responseCode = "200", description = "The member's profiles")
    @ApiResponse(responseCode = "401", description = "Not signed in", content = @Content)
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    List<BillingProfileResponse> list(Principal principal) {
        return profiles.list(caller(principal)).stream().map(BillingProfileResponse::from).toList();
    }

    @Operation(operationId = "getDefaultBillingProfile", summary = "Read the member's default invoice details",
            description = "profile is null when the member has none.")
    @ApiResponse(responseCode = "200", description = "The default profile, or null")
    @ApiResponse(responseCode = "401", description = "Not signed in", content = @Content)
    @GetMapping(value = "/default", produces = MediaType.APPLICATION_JSON_VALUE)
    BillingProfileResponse.Default getDefault(Principal principal) {
        return new BillingProfileResponse.Default(
                profiles.findDefault(caller(principal)).map(BillingProfileResponse::from).orElse(null));
    }

    @Operation(operationId = "getBillingProfile", summary = "Read one of the member's invoice details")
    @ApiResponse(responseCode = "200", description = "The profile")
    @ApiResponse(responseCode = "401", description = "Not signed in", content = @Content)
    @ApiResponse(responseCode = "404", description = "Not the member's profile (code billing_profile_not_found)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    BillingProfileResponse get(Principal principal, @PathVariable String id) {
        return profiles.find(caller(principal), parseId(id)).map(BillingProfileResponse::from)
                .orElseThrow(BillingProfileNotFoundException::new);
    }

    @Operation(operationId = "createBillingProfile", summary = "Add invoice details",
            description = "The member's first profile becomes the default whatever isDefault says. A member holds"
                    + " at most 20 profiles.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(schema = @Schema(implementation = BillingProfileResponse.Request.class))))
    @ApiResponse(responseCode = "201", description = "The stored profile")
    @ApiResponse(responseCode = "401", description = "Not signed in", content = @Content)
    @ApiResponse(responseCode = "422",
            description = "billing_profile_invalid with errors.<field> (type, legalName, taxCode,"
                    + " personalIdNumber, address, email, isDefault), or billing_profile_limit_reached when the"
                    + " member already holds 20 profiles",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    BillingProfileResponse create(Principal principal, @RequestBody JsonNode body) {
        return BillingProfileResponse.from(profiles.create(caller(principal), body));
    }

    @Operation(operationId = "updateBillingProfile", summary = "Edit invoice details",
            description = "Fields present replace the stored ones, null clears, absent ones are kept; the merged"
                    + " profile must pass the same rules as a new one.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(schema = @Schema(implementation = BillingProfileResponse.Request.class))))
    @ApiResponse(responseCode = "200", description = "The stored profile")
    @ApiResponse(responseCode = "401", description = "Not signed in", content = @Content)
    @ApiResponse(responseCode = "404", description = "Not the member's profile (code billing_profile_not_found)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "billing_profile_invalid with errors.<field>",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PatchMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    BillingProfileResponse update(Principal principal, @PathVariable String id, @RequestBody JsonNode body) {
        return BillingProfileResponse.from(profiles.update(caller(principal), parseId(id), body));
    }

    @Operation(operationId = "setDefaultBillingProfile", summary = "Make one of the member's profiles the default")
    @ApiResponse(responseCode = "200", description = "The profile, now the default")
    @ApiResponse(responseCode = "401", description = "Not signed in", content = @Content)
    @ApiResponse(responseCode = "404", description = "Not the member's profile (code billing_profile_not_found)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PatchMapping(value = "/{id}/default", produces = MediaType.APPLICATION_JSON_VALUE)
    BillingProfileResponse setDefault(Principal principal, @PathVariable String id) {
        return BillingProfileResponse.from(profiles.setDefault(caller(principal), parseId(id)));
    }

    @Operation(operationId = "deleteBillingProfile", summary = "Delete invoice details",
            description = "When the default is deleted the oldest remaining profile becomes the default.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "401", description = "Not signed in", content = @Content)
    @ApiResponse(responseCode = "404", description = "Not the member's profile (code billing_profile_not_found)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(Principal principal, @PathVariable String id) {
        profiles.delete(caller(principal), parseId(id));
    }

    private static UUID caller(Principal principal) {
        return UUID.fromString(principal.getName());
    }

    private static UUID parseId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new BillingProfileNotFoundException();
        }
    }
}
