package com.flowcortex.asset.application;

import com.flowcortex.asset.domain.AssetRequestRejected;
import com.flowcortex.asset.domain.RegistrationView;
import com.flowcortex.asset.infrastructure.RunResultQueries;
import com.flowcortex.asset.infrastructure.RunResultQueries.RunResultRow;
import com.flowcortex.asset.infrastructure.RunResultQueries.ThumbnailRow;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * What the library holds for a page of runs: how many files each produced, of which kinds, a few thumbnails
 * and whether their registration is still running. One SQL statement and no object-store call answers any
 * number of runs; a run that is unknown or another member's answers like one that produced nothing.
 */
@Service
public class RunResults {

    static final int MAX_RUNS = 100;

    private static final Logger log = LoggerFactory.getLogger(RunResults.class);

    /** One run's summary; {@code thumbnails} are signed inline links, at most three. */
    public record RunResult(UUID runId, long count, List<String> kinds, List<String> thumbnails,
            RegistrationView registration) {
    }

    private final RunResultQueries queries;
    private final AssetViews views;

    public RunResults(RunResultQueries queries, AssetViews views) {
        this.queries = queries;
        this.views = views;
    }

    /**
     * The summaries of 1-100 distinct runs, in the order the ids were first sent; repeated ids answer once.
     *
     * @throws AssetRequestRejected naming {@code runId} when none is sent, one is not a UUID or more than 100 are distinct
     */
    public List<RunResult> results(UUID spaceId, UUID ownerId, List<String> runIdTexts) {
        List<UUID> runIds = distinctRunIds(runIdTexts);
        List<RunResult> results = queries.results(spaceId, ownerId, runIds).stream().map(this::view).toList();
        log.info("asset.library.runResults space={} outcome=ok runs={}", spaceId, results.size());
        return results;
    }

    private static List<UUID> distinctRunIds(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            throw new AssetRequestRejected("runId");
        }
        Set<UUID> distinct = new LinkedHashSet<>();
        for (String text : texts) {
            UUID id = text == null ? null : SpaceAssetLibrary.parseId(text.strip());
            if (id == null) {
                throw new AssetRequestRejected("runId");
            }
            distinct.add(id);
        }
        if (distinct.size() > MAX_RUNS) {
            throw new AssetRequestRejected("runId");
        }
        return List.copyOf(distinct);
    }

    private RunResult view(RunResultRow row) {
        List<String> thumbnails = row.thumbnails().stream().map(this::sign).filter(Objects::nonNull).toList();
        return new RunResult(row.runId(), row.count(), row.kinds(), thumbnails,
                RegistrationView.ofColumn(row.registrationStatus()));
    }

    private String sign(ThumbnailRow thumbnail) {
        return views.signedThumbnail(thumbnail.id(), thumbnail.title(), thumbnail.mimeType(), thumbnail.bucket(),
                thumbnail.objectKey());
    }
}
