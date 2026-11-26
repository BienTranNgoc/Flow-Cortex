package com.flowcortex.workflow.web;

import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.workflow.api.MarketplaceItemView;
import com.flowcortex.workflow.application.MarketplaceService.CatalogueStats;
import com.flowcortex.workflow.application.MarketplaceService.MarketplacePage;
import com.flowcortex.workflow.application.MarketplaceService.StepTypeFacet;
import com.flowcortex.workflow.application.MarketplaceService.WorkflowCounts;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;
import java.util.List;

/**
 * The answers of the marketplace routes. Each record carries an explicit schema name: the nested simple names
 * ({@code Item}, {@code Meta}) are already taken by other responses in the contract.
 */
final class MarketplaceResponses {

    private MarketplaceResponses() {
    }

    /** {@code authorName} is null for a system-owned workflow; no author id or email is returned. */
    @Schema(name = "MarketplaceItem")
    record Item(
            @Schema(requiredMode = RequiredMode.REQUIRED) WorkflowResponse workflow,
            @Schema(nullable = true) String authorName,
            @Schema(requiredMode = RequiredMode.REQUIRED) Rating rating,
            @Schema(requiredMode = RequiredMode.REQUIRED) int membersLast30Days,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean bookmarked) {

        static Item from(MarketplaceItemView view, CreditAccounts credits) {
            return new Item(WorkflowResponse.from(view.workflow(), credits), view.authorName(),
                    new Rating(view.ratingAverage(), view.ratingCount()), view.membersLast30Days(),
                    view.bookmarked());
        }

        static List<Item> from(List<MarketplaceItemView> views, CreditAccounts credits) {
            return views.stream().map(view -> from(view, credits)).toList();
        }
    }

    /** {@code average} is the mean rating at two decimals, null without reviews. */
    @Schema(name = "MarketplaceRating")
    record Rating(
            @Schema(nullable = true) BigDecimal average,
            @Schema(requiredMode = RequiredMode.REQUIRED) int count) {
    }

    @Schema(name = "MarketplacePageMeta")
    record Meta(
            @Schema(requiredMode = RequiredMode.REQUIRED) long total,
            @Schema(requiredMode = RequiredMode.REQUIRED) int page,
            @Schema(requiredMode = RequiredMode.REQUIRED) int size) {
    }

    @Schema(name = "MarketplacePageResponse")
    record Page(
            @Schema(requiredMode = RequiredMode.REQUIRED) List<Item> data,
            @Schema(requiredMode = RequiredMode.REQUIRED) Meta meta) {

        static Page from(MarketplacePage found, int page, int size, CreditAccounts credits) {
            return new Page(Item.from(found.data(), credits), new Meta(found.total(), page, size));
        }
    }

    @Schema(name = "MarketplaceItemsResponse")
    record Items(@Schema(requiredMode = RequiredMode.REQUIRED) List<Item> data) {
    }

    @Schema(name = "MarketplaceStepTypeFacet")
    record Facet(
            @Schema(requiredMode = RequiredMode.REQUIRED) String type,
            @Schema(requiredMode = RequiredMode.REQUIRED) long count) {
    }

    @Schema(name = "MarketplaceFacetsResponse")
    record Facets(@Schema(requiredMode = RequiredMode.REQUIRED) List<Facet> stepTypes) {

        static Facets from(List<StepTypeFacet> facets) {
            return new Facets(facets.stream().map(facet -> new Facet(facet.type(), facet.count())).toList());
        }
    }

    @Schema(name = "MarketplaceStatsResponse")
    record Stats(
            @Schema(requiredMode = RequiredMode.REQUIRED) long totalWorkflows,
            @Schema(requiredMode = RequiredMode.REQUIRED) long totalBuilders) {

        static Stats from(CatalogueStats stats) {
            return new Stats(stats.totalWorkflows(), stats.totalBuilders());
        }
    }

    /** {@code saved} equals the {@code meta.total} of the saved list. */
    @Schema(name = "WorkflowCountsResponse")
    record Counts(
            @Schema(requiredMode = RequiredMode.REQUIRED) long mine,
            @Schema(requiredMode = RequiredMode.REQUIRED) long saved) {

        static Counts from(WorkflowCounts counts) {
            return new Counts(counts.mine(), counts.saved());
        }
    }
}
