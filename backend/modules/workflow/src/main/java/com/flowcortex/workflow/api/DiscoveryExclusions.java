package com.flowcortex.workflow.api;

import java.util.Set;
import java.util.UUID;

/**
 * Workflows another module asks the discovery reads to leave out of the recommended and similar lists; they stay in
 * browse, facets and totals. A module that owns such a workflow implements this port, since it depends on this
 * module and not the other way round.
 */
public interface DiscoveryExclusions {

    Set<UUID> workflowIds();
}
