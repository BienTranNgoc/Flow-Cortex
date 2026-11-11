package com.flowcortex.workflow.infrastructure;

/** How many reviews of a workflow gave one star rating. */
public interface RatingCount {

    int getRating();

    long getTotal();
}
