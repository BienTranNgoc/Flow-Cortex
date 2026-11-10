package com.flowcortex.workflow.api.runhistory;

import java.util.List;

/** The newest runs matching an export's filters, capped, and how many runs match in all. */
public record RunExport(List<RunHistoryItem> items, long total) {
}
