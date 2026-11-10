package com.flowcortex.billing.journey;

import com.flowcortex.billing.journey.PaymentJourneyEvent.PayerReturned;
import com.flowcortex.billing.journey.PaymentJourneyEvent.PaymentOpened;
import com.flowcortex.shared.ratelimit.RateLimiter;
import com.flowcortex.shared.ratelimit.RateLimiters;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.security.Principal;
import java.time.Duration;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Records what a payment page says its payer saw. An observation only: it reads no row, calls no provider and
 * changes nothing, and the caller's identity is used for the rate limit alone and never logged.
 */
@RestController
@RequestMapping("/api/v1/billing")
class PaymentJourneyController {

    private final RateLimiter limiter;
    private final JourneyTelemetry telemetry;

    PaymentJourneyController(RateLimiters rateLimiters, JourneyTelemetry telemetry) {
        this.limiter = rateLimiters.named(JourneyRateLimits.NAME);
        this.telemetry = telemetry;
    }

    @Operation(operationId = "recordPaymentJourneyEvent", summary = "Record a payment journey observation",
            description = "Records one observation a payment page made. Never changes a payment.")
    @RequestBody(required = true,
            description = "One body of contracts/payment-journey-event.schema.json: billing.payment_opened or"
                    + " billing.payer_returned. Any other body is refused whole.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object")))
    @ApiResponse(responseCode = "204", description = "Recorded")
    @ApiResponse(responseCode = "400", description = "Not a journey event this service records (code journey_event_invalid)")
    @ApiResponse(responseCode = "401", description = "Not signed in")
    @ApiResponse(responseCode = "415", description = "Not application/json")
    @ApiResponse(responseCode = "429", description = "More than 30 requests in a minute; Retry-After gives the wait")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping(value = "/journey-events", consumes = MediaType.APPLICATION_JSON_VALUE)
    void record(Principal principal, HttpServletRequest request) {
        // The limit runs before the body is read, so a flood is refused without parsing.
        Optional<Duration> wait = limiter.tryAcquire(principal.getName());
        if (wait.isPresent()) {
            throw JourneyRateLimitedException.of(wait.get());
        }
        PaymentJourneyEvent event = readBody(request)
                .flatMap(PaymentJourneyEventParser::parse)
                .orElseThrow(() -> {
                    telemetry.refused();
                    return new JourneyEventRefusedException();
                });
        switch (event) {
            case PaymentOpened opened -> telemetry.opened(opened);
            case PayerReturned returned -> telemetry.returned(returned);
        }
    }

    /** At most one byte past the ceiling, so an oversize body is detected without being read whole. */
    private static Optional<byte[]> readBody(HttpServletRequest request) {
        try {
            return Optional.of(request.getInputStream().readNBytes(PaymentJourneyEventParser.MAX_BODY_BYTES + 1));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
