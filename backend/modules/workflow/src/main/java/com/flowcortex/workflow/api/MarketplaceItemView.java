package com.flowcortex.workflow.api;

import java.math.BigDecimal;

/**
 * A catalogue workflow as a member sees it. {@code authorName} is the owner's display name, null for a system-owned
 * workflow; no author id or email is carried. {@code ratingAverage} is the mean rating at scale 2, null while
 * {@code ratingCount} is zero. {@code bookmarked} says whether the caller saved it.
 */
public record MarketplaceItemView(WorkflowView workflow, String authorName, BigDecimal ratingAverage,
        int ratingCount, int membersLast30Days, boolean bookmarked) {
}
