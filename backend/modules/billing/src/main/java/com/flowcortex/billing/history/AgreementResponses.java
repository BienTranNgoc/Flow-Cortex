package com.flowcortex.billing.history;

import com.flowcortex.billing.history.AgreementReader.Agreement;
import com.flowcortex.billing.history.AgreementReader.AgreementRow;
import com.flowcortex.billing.history.AgreementReader.Page;
import com.flowcortex.billing.history.AgreementReader.Purchase;
import com.flowcortex.billing.terms.AcceptedTermsReader.Provenance;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** The answers of the agreement routes. */
final class AgreementResponses {

    private AgreementResponses() {
    }

    /** What was bought; {@code acceptedAt} is when the payment was opened, {@code collectedAt} when it was paid. */
    @Schema(name = "AgreementPurchaseResponse")
    record PurchaseView(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID transactionId,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant acceptedAt,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) Instant collectedAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) long amountVnd,
            @Schema(requiredMode = RequiredMode.REQUIRED) String currency,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"plan_purchase", "topup"}) String kind,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"pending", "succeeded", "failed",
                "expired", "partially_refunded", "refunded", "disputed"}) String status,
            @Schema(requiredMode = RequiredMode.REQUIRED) String description) {

        static PurchaseView from(Purchase purchase) {
            return new PurchaseView(purchase.transactionId(), purchase.acceptedAt(), purchase.collectedAt(),
                    purchase.amountVnd(), purchase.currency(), purchase.kind(), purchase.status(),
                    purchase.description());
        }
    }

    /**
     * Where the terms come from: {@code recorded} (pinned when the payment was opened), {@code reconstructed} (the
     * publication in force when it was opened) or {@code no_record} (the document published now, no revision id).
     */
    @Schema(name = "AgreementTermsResponse")
    record TermsView(
            @Schema(requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"recorded", "reconstructed", "no_record"}) String source,
            @Schema(requiredMode = RequiredMode.REQUIRED) String slug,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) UUID revisionId,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant publishedAt) {

        static TermsView from(Provenance terms) {
            return new TermsView(terms.source(), terms.slug(), terms.revisionId(), terms.publishedAt());
        }
    }

    /** One purchase of the list with where its terms come from. */
    @Schema(name = "AgreementSummaryResponse")
    record Summary(
            @Schema(requiredMode = RequiredMode.REQUIRED) PurchaseView purchase,
            @Schema(requiredMode = RequiredMode.REQUIRED) TermsView terms) {

        static Summary from(AgreementRow row) {
            return new Summary(PurchaseView.from(row.purchase()), TermsView.from(row.terms()));
        }
    }

    /** Where the page sits: {@code page} counts from 0. */
    @Schema(name = "AgreementListMeta")
    record ListMeta(
            @Schema(requiredMode = RequiredMode.REQUIRED) long total,
            @Schema(requiredMode = RequiredMode.REQUIRED) int page,
            @Schema(requiredMode = RequiredMode.REQUIRED) int size) {
    }

    /** One newest-first page of the member's purchases; {@code meta.total} counts all of them. */
    @Schema(name = "AgreementListResponse")
    record ListResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) List<Summary> data,
            @Schema(requiredMode = RequiredMode.REQUIRED) ListMeta meta) {

        static ListResponse from(Page page, HistoryQuery query) {
            return new ListResponse(page.rows().stream().map(Summary::from).toList(),
                    new ListMeta(page.total(), query.page(), query.size()));
        }
    }

    /** One purchase, where its terms come from, and the text of those terms as published. */
    @Schema(name = "AgreementDetailResponse")
    record Detail(
            @Schema(requiredMode = RequiredMode.REQUIRED) PurchaseView purchase,
            @Schema(requiredMode = RequiredMode.REQUIRED) TermsView terms,
            @Schema(requiredMode = RequiredMode.REQUIRED, type = "array",
                    description = "The document's sections as published: [{type, schemaVersion, data}]")
            JsonNode sections,
            @Schema(requiredMode = RequiredMode.REQUIRED, type = "object",
                    description = "The document's SEO text as published: {title, description} in vi and en")
            JsonNode seoMeta) {

        static Detail from(Agreement agreement) {
            return new Detail(PurchaseView.from(agreement.purchase()), TermsView.from(agreement.terms()),
                    agreement.sections(), agreement.seoMeta());
        }
    }
}
