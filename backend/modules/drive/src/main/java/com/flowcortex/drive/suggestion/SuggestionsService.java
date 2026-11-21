package com.flowcortex.drive.suggestion;

import com.flowcortex.drive.domain.SuggestionProjector;
import com.flowcortex.drive.domain.SuggestionProjector.Suggestion;
import com.flowcortex.media.api.MediaDownload;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.workflow.api.RunResultView.Output;
import com.flowcortex.workflow.api.RunResults;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Read-only candidates from the owner's newest completed runs; nothing is written. */
@Service
class SuggestionsService {

    /** A candidate with the signed preview of its file ({@code null} for a text candidate). */
    record SuggestionView(Suggestion suggestion, MediaDownload preview) {
    }

    private final RunResults runResults;
    private final MediaJobs mediaJobs;

    SuggestionsService(RunResults runResults, MediaJobs mediaJobs) {
        this.runResults = runResults;
        this.mediaJobs = mediaJobs;
    }

    List<SuggestionView> list(UUID ownerId, int limit) {
        return SuggestionProjector.project(runResults.recentCompletedRuns(ownerId, limit), limit).stream()
                .map(suggestion -> new SuggestionView(suggestion, preview(suggestion.output())))
                .toList();
    }

    private MediaDownload preview(Output output) {
        return output == null ? null : mediaJobs.presignedGet(output.bucket(), output.objectKey(), output.name());
    }
}
