package com.flowcortex.billing.refund;

import com.flowcortex.billing.refund.RefundResponses.ChangeOfMindResponse;
import com.flowcortex.billing.refund.RefundResponses.LatestRefundRequestResponse;
import com.flowcortex.billing.refund.RefundResponses.RefundEligibilityResponse;
import com.flowcortex.billing.refund.RefundResponses.RefundRequestView;
import com.flowcortex.shared.ratelimit.RateLimiter;
import com.flowcortex.shared.ratelimit.RateLimiters;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The member side of the change-of-mind refund. The caller is the token's subject and every read and write is
 * filtered by it, so a payment of another member, an unknown one and a malformed identifier all answer the
 * same 404. The two writes share one per-member budget.
 */
@RestController
@RequestMapping("/api/v1/billing")
class RefundRequestsController {

    private static final String PROBLEM = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

    private final RefundRequests refunds;
    private final RateLimiter limiter;

    RefundRequestsController(RefundRequests refunds, RateLimiters rateLimiters) {
        this.refunds = refunds;
        this.limiter = rateLimiters.named(RefundRateLimits.NAME);
    }

    /** The optional body of a request; the reason is validated on the raw text, then trimmed by the service. */
    record RefundRequestBody(
            @Schema(nullable = true) @Size(min = 10, max = 2000) String reason) {
    }

    @Operation(operationId = "getChangeOfMindRight", summary = "Read the change-of-mind right of the latest plan purchase",
            description = "With the surface switch off nothing is computed. Otherwise the verdict of the member's"
                    + " newest collected plan purchase, with its latest request, and the member's turn standing.")
    @ApiResponse(responseCode = "200", description = "The right, or none")
    @ApiResponse(responseCode = "401", description = "Not signed in", content = @Content)
    @GetMapping(value = "/change-of-mind", produces = MediaType.APPLICATION_JSON_VALUE)
    ChangeOfMindResponse changeOfMind(Principal principal) {
        return refunds.changeOfMind(caller(principal));
    }

    @Operation(operationId = "getRefundEligibility", summary = "Read the refund right over one of the member's payments")
    @ApiResponse(responseCode = "200", description = "The verdict, the turn standing and the latest request")
    @ApiResponse(responseCode = "401", description = "Not signed in", content = @Content)
    @ApiResponse(responseCode = "404", description = "Not the member's payment (code transaction_not_found)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(value = "/transactions/{transactionId}/refund-eligibility", produces = MediaType.APPLICATION_JSON_VALUE)
    RefundEligibilityResponse eligibility(Principal principal, @PathVariable String transactionId) {
        return refunds.eligibility(caller(principal), parseId(transactionId));
    }

    @Operation(operationId = "getLatestRefundRequest", summary = "Read the latest refund request on a payment")
    @ApiResponse(responseCode = "200", description = "The latest request, or null when there is none")
    @ApiResponse(responseCode = "401", description = "Not signed in", content = @Content)
    @ApiResponse(responseCode = "404", description = "Not the member's payment (code transaction_not_found)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(value = "/transactions/{transactionId}/refund-request", produces = MediaType.APPLICATION_JSON_VALUE)
    LatestRefundRequestResponse latest(Principal principal, @PathVariable String transactionId) {
        return refunds.latest(caller(principal), parseId(transactionId));
    }

    @Operation(operationId = "requestRefund", summary = "Ask for the change-of-mind refund of a payment",
            description = "Answers the payment's pending request when there is one; otherwise records a pending"
                    + " request when the right is permitted.")
    @ApiResponse(responseCode = "200", description = "The pending request")
    @ApiResponse(responseCode = "400", description = "The reason is present and not 10 to 2000 characters (code validation-failed)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Not signed in", content = @Content)
    @ApiResponse(responseCode = "404", description = "Not the member's payment (code transaction_not_found)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The right is refused (code refund_not_eligible, with refusal)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "More than 10 requests or cancels in a minute; Retry-After gives the wait",
            content = @Content)
    @PostMapping(value = "/transactions/{transactionId}/refund-request", produces = MediaType.APPLICATION_JSON_VALUE)
    RefundRequestView request(Principal principal, @PathVariable String transactionId,
            @Valid @RequestBody(required = false) RefundRequestBody body) {
        acquire(principal);
        UUID member = caller(principal);
        return refunds.request(member, parseId(transactionId), body == null ? null : body.reason());
    }

    @Operation(operationId = "cancelRefundRequest", summary = "Cancel the pending refund request of a payment")
    @ApiResponse(responseCode = "200", description = "The cancelled request")
    @ApiResponse(responseCode = "401", description = "Not signed in", content = @Content)
    @ApiResponse(responseCode = "404", description = "Not the member's payment (code transaction_not_found)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "No pending request (code refund_request_not_cancellable)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "More than 10 requests or cancels in a minute; Retry-After gives the wait",
            content = @Content)
    @PostMapping(value = "/transactions/{transactionId}/refund-request/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    RefundRequestView cancel(Principal principal, @PathVariable String transactionId) {
        acquire(principal);
        UUID member = caller(principal);
        return refunds.cancel(member, parseId(transactionId));
    }

    /** Runs before the path id is parsed, so a flood is refused whatever it names. */
    private void acquire(Principal principal) {
        Optional<Duration> wait = limiter.tryAcquire(principal.getName());
        if (wait.isPresent()) {
            throw RefundRateLimitedException.of(wait.get());
        }
    }

    private static UUID caller(Principal principal) {
        return UUID.fromString(principal.getName());
    }

    private static UUID parseId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new RefundTransactionNotFoundException();
        }
    }
}
