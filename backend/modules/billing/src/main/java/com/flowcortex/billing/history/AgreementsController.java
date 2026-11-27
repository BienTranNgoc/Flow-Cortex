package com.flowcortex.billing.history;

import com.flowcortex.billing.history.AgreementResponses.Detail;
import com.flowcortex.billing.history.AgreementResponses.ListResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The payment terms behind a member's own purchases. The caller is the token's subject and every read is
 * filtered by it, so a payment of another member, an unknown one and a malformed identifier all answer the same
 * 404.
 */
@RestController
@RequestMapping("/api/v1/billing/agreements")
class AgreementsController {

    private final AgreementReader reader;

    AgreementsController(AgreementReader reader) {
        this.reader = reader;
    }

    @Operation(operationId = "listPurchaseAgreements", summary = "List the member's purchases with their terms",
            description = "Newest first. Each entry says which publication of the payment terms the purchase is"
                    + " under, without the text.")
    @ApiResponse(responseCode = "200", description = "One page of the member's purchases")
    @ApiResponse(responseCode = "400", description = "page or size out of range (code invalid_pagination)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Not signed in", content = @Content)
    @ApiResponse(responseCode = "503",
            description = "A purchase has no terms to show (code payment_agreement_terms_unavailable)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ListResponse list(Principal principal,
            @Parameter(description = "Page number from 0 to 10000; default 0",
                    schema = @Schema(type = "integer", minimum = "0", maximum = "10000", defaultValue = "0"))
            @RequestParam(required = false) String page,
            @Parameter(description = "Rows per page from 1 to 100; default 20",
                    schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20"))
            @RequestParam(required = false) String size) {
        HistoryQuery query = HistoryQuery.parse(page, size, null);
        return ListResponse.from(reader.page(caller(principal), query), query);
    }

    @Operation(operationId = "getPurchaseAgreement", summary = "Read the terms behind one of the member's purchases")
    @ApiResponse(responseCode = "200", description = "The purchase, where its terms come from, and their text")
    @ApiResponse(responseCode = "401", description = "Not signed in", content = @Content)
    @ApiResponse(responseCode = "404", description = "Not the member's payment (code payment_agreement_not_found)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503",
            description = "No terms to show (code payment_agreement_terms_unavailable)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(value = "/{transactionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    Detail get(Principal principal, @PathVariable String transactionId) {
        return reader.find(caller(principal), parseId(transactionId)).map(Detail::from)
                .orElseThrow(AgreementNotFoundException::new);
    }

    private static UUID caller(Principal principal) {
        return UUID.fromString(principal.getName());
    }

    private static UUID parseId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new AgreementNotFoundException();
        }
    }
}
