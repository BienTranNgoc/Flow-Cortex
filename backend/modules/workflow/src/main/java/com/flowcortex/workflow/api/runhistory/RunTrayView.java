package com.flowcortex.workflow.api.runhistory;

import java.util.List;

/** The member's live runs (newest start first) and those finished within the last day (latest finish first). */
public record RunTrayView(List<RunHistoryItem> active, List<RunHistoryItem> recent) {
}
