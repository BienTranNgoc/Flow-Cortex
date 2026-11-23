package com.flowcortex.billing.history;

import com.flowcortex.billing.payment.PaymentOperationRefs;
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
 * A member's own payments. The caller is the token's subject and every read is filtered by it, so a payment of
 * another member, an unknown one and a malformed identifier all answer the same 404.
 */
@RestController
@RequestMapping("/api/v1/billing/transactions")
class TransactionsController {

    private final TransactionHistoryReader reader;

    TransactionsController(TransactionHistoryReader reader) {
        this.reader = reader;
    }

    @Operation(operationId = "listBillingTransactions", summary = "List the member's payments",
            description = "Newest first. page counts from 0; settled=true keeps payments where money reached us.")
    @ApiResponse(responseCode = "200", description = "One page of the member's payments")
    @ApiResponse(responseCode = "400",
            description = "page or size out of range (code invalid_pagination), or settled not a flag"
                    + " (code invalid_query_flag)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Not signed in", content = @Content)
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    TransactionListResponse list(Principal principal,
            @Parameter(description = "Page number from 0 to 10000; default 0",
                    schema = @Schema(type = "integer", minimum = "0", maximum = "10000", defaultValue = "0"))
            @RequestParam(required = false) String page,
            @Parameter(description = "Rows per page from 1 to 100; default 20",
                    schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20"))
            @RequestParam(required = false) String size,
            @Parameter(description = "true or 1 lists only succeeded, partially refunded and refunded payments",
                    schema = @Schema(type = "boolean", defaultValue = "false"))
            @RequestParam(required = false) String settled) {
        HistoryQuery query = HistoryQuery.parse(page, size, settled);
        return TransactionListResponse.from(reader.page(caller(principal), query), query);
    }

    @Operation(operationId = "getBillingTransaction", summary = "Read one of the member's payments")
    @ApiResponse(responseCode = "200", description = "The payment with its provider facts and timeline")
    @ApiResponse(responseCode = "401", description = "Not signed in", content = @Content)
    @ApiResponse(responseCode = "404", description = "Not the member's payment (code transaction_not_found)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(value = "/{transactionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    TransactionDetailResponse get(Principal principal, @PathVariable String transactionId) {
        return reader.findOwn(caller(principal), parseId(transactionId))
                .map(TransactionDetailResponse::from)
                .orElseThrow(TransactionNotFoundException::new);
    }

    @Operation(operationId = "getBillingTransactionByOrder",
            summary = "Read one of the member's payments by order reference")
    @ApiResponse(responseCode = "200", description = "The payment with its provider facts and timeline")
    @ApiResponse(responseCode = "401", description = "Not signed in", content = @Content)
    @ApiResponse(responseCode = "404", description = "Not the member's payment (code transaction_not_found)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(value = "/by-order/{orderRef}", produces = MediaType.APPLICATION_JSON_VALUE)
    TransactionDetailResponse getByOrder(Principal principal, @PathVariable String orderRef) {
        if (!PaymentOperationRefs.isOrderRef(orderRef)) {
            throw new TransactionNotFoundException();
        }
        return reader.findOwnByOrder(caller(principal), orderRef)
                .map(TransactionDetailResponse::from)
                .orElseThrow(TransactionNotFoundException::new);
    }

    private static UUID caller(Principal principal) {
        return UUID.fromString(principal.getName());
    }

    private static UUID parseId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new TransactionNotFoundException();
        }
    }
}
