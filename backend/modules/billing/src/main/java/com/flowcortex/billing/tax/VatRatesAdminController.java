package com.flowcortex.billing.tax;

import com.flowcortex.billing.tax.VatRateTimelineResponse.VatRateView;
import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.security.authz.RequirePermission;
import com.flowcortex.shared.web.audit.AdminAudit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/admin/vat-rates}: reads the VAT rate timeline and sets the rate in force from today.
 */
@RestController
@RequestMapping("/api/v1/admin/vat-rates")
class VatRatesAdminController {

    private static final int MAX_NOTE_LENGTH = 1000;
    private static final int MAX_RATE_BPS = 10_000;

    private final VatRates rates;
    private final VatRatePublisher publisher;

    VatRatesAdminController(VatRates rates, VatRatePublisher publisher) {
        this.rates = rates;
        this.publisher = publisher;
    }

    /**
     * {@code rateBps} is a whole number of basis points; it is bound as a decimal so a fractional value such as
     * 850.5 is refused instead of being coerced to 850.
     */
    @Schema(name = "PublishVatRateRequest")
    record PublishVatRateRequest(
            @Schema(nullable = true, allowableValues = VatRates.JURISDICTION) @Pattern(regexp = VatRates.JURISDICTION)
            String jurisdiction,
            @Schema(nullable = true, allowableValues = VatRates.CATEGORY) @Pattern(regexp = VatRates.CATEGORY)
            String category,
            @Schema(requiredMode = RequiredMode.REQUIRED, implementation = Integer.class, minimum = "0",
                    maximum = "10000")
            @NotNull @DecimalMin("0") @DecimalMax("10000") @Digits(integer = 5, fraction = 0) BigDecimal rateBps,
            @Schema(nullable = true, maxLength = MAX_NOTE_LENGTH) @Size(max = MAX_NOTE_LENGTH) String sourceNote) {
    }

    @Operation(operationId = "getVatRateTimeline")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "validation-failed (errors.jurisdiction, errors.category)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "forbidden (needs payment.setting.view)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.PAYMENT_SETTING_VIEW)
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    VatRateTimelineResponse timeline(
            @RequestParam(defaultValue = VatRates.JURISDICTION) @Pattern(regexp = VatRates.JURISDICTION)
            String jurisdiction,
            @RequestParam(defaultValue = VatRates.CATEGORY) @Pattern(regexp = VatRates.CATEGORY) String category) {
        return VatRateTimelineResponse.of(jurisdiction, category, rates.timeline(jurisdiction, category));
    }

    @Operation(operationId = "publishVatRate")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400",
            description = "validation-failed (errors.rateBps, errors.jurisdiction, errors.category, errors.sourceNote)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "forbidden (needs payment.setting.update)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409",
            description = "vat_rate_no_open_row, vat_rate_multiple_open_rows, vat_rate_no_precedent or vat_rate_in_use",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @AdminAudit(value = "vat_rate.publish", resourceParam = "publishedRateId",
            capture = {"jurisdiction", "category", "rateBps"})
    @RequirePermission(Permission.PAYMENT_SETTING_UPDATE)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    VatRatePublishedResponse publish(Principal caller, @Valid @RequestBody PublishVatRateRequest body) {
        String jurisdiction = body.jurisdiction() == null ? VatRates.JURISDICTION : body.jurisdiction();
        String category = body.category() == null ? VatRates.CATEGORY : body.category();
        UUID actor = UUID.fromString(caller.getName());
        PublishedVatRate published = publisher.publish(
                new PublishVatRate(jurisdiction, category, body.rateBps().intValueExact(), body.sourceNote()), actor);
        return new VatRatePublishedResponse(jurisdiction, category,
                published.rates().stream().map(VatRateView::of).toList(), published.publishedRateId());
    }
}
