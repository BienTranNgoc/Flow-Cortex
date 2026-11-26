package com.flowcortex.app.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** {@code GET /api/v1/assets/run-results} over HTTP, with the rows each scenario needs written by the test. */
class RunResultsEndpointsIT extends AbstractAssetHttpIT {

    record ThumbnailView(String url) {
    }

    record ResultView(UUID runId, long count, List<String> kinds, List<ThumbnailView> thumbnails,
            String registration) {
    }

    record ResultsView(List<ResultView> results) {
    }

    @Test
    void mixedOutputsWithAMissingImageCountThreeOneThumbnail() {
        Member member = member("results-mixed");
        UUID run = UUID.randomUUID();
        Instant base = Instant.now().minus(Duration.ofHours(1));
        member.output("image", "kept", run, base);
        UUID gone = member.output("image", "gone", run, base.plusSeconds(1));
        member.output("video", "clip", run, base.plusSeconds(2));
        member.markMissing(gone);

        ResultView result = results(member, run).getFirst();

        assertThat(result.runId()).isEqualTo(run);
        assertThat(result.count()).isEqualTo(3);
        assertThat(result.kinds()).containsExactly("video", "image");
        assertThat(result.thumbnails()).hasSize(1);
        assertThat(result.thumbnails().getFirst().url()).isNotBlank();
        assertThat(result.registration()).isEqualTo("none");
    }

    @Test
    void aRunShowsAtMostThreeThumbnailsOfItsNewestImages() {
        Member member = member("results-three");
        UUID run = UUID.randomUUID();
        Instant base = Instant.now().minus(Duration.ofHours(1));
        for (int i = 0; i < 5; i++) {
            member.output("image", "image " + i, run, base.plusSeconds(i));
        }

        ResultView result = results(member, run).getFirst();

        assertThat(result.count()).isEqualTo(5);
        assertThat(result.thumbnails()).hasSize(3);
    }

    @Test
    void inputsAreNotCounted() {
        Member member = member("results-inputs");
        UUID run = UUID.randomUUID();
        Instant base = Instant.now().minus(Duration.ofHours(1));
        member.input("image", "input one", run, base);
        member.input("image", "input two", run, base.plusSeconds(1));
        member.output("video", "output", run, base.plusSeconds(2));

        ResultView result = results(member, run).getFirst();

        assertThat(result.count()).isEqualTo(1);
        assertThat(result.kinds()).containsExactly("video");
        assertThat(result.thumbnails()).isEmpty();
    }

    @Test
    void anotherMembersRunIsEmptyAndNone() {
        Member owner = member("results-owner");
        Member other = member("results-other");
        UUID run = UUID.randomUUID();
        Instant base = Instant.now().minus(Duration.ofHours(1));
        for (int i = 0; i < 4; i++) {
            owner.output("image", "image " + i, run, base.plusSeconds(i));
        }
        owner.registration(run, owner.userId(), "done");

        ResultView seenByOther = results(other, run).getFirst();

        assertThat(seenByOther.runId()).isEqualTo(run);
        assertThat(seenByOther.count()).isZero();
        assertThat(seenByOther.kinds()).isEmpty();
        assertThat(seenByOther.thumbnails()).isEmpty();
        assertThat(seenByOther.registration()).isEqualTo("none");
        assertThat(results(owner, run).getFirst().registration()).isEqualTo("done");
    }

    @Test
    void pendingRegistrationReadsPending() {
        Member member = member("results-registration");
        UUID pending = UUID.randomUUID();
        UUID done = UUID.randomUUID();
        UUID skipped = UUID.randomUUID();
        UUID stuck = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();
        member.registration(pending, member.userId(), "pending");
        member.registration(done, member.userId(), "done");
        member.registration(skipped, member.userId(), "skipped");
        member.registration(stuck, member.userId(), "stuck");
        member.registration(foreign, UUID.randomUUID(), "pending");

        List<ResultView> results = results(member, pending, done, skipped, stuck, foreign);

        assertThat(results).extracting(ResultView::registration)
                .containsExactly("pending", "done", "none", "none", "none");
        assertThat(results.getFirst().count()).isZero();
    }

    @Test
    void repeatedIdsAnswerOncePerRunInFirstSeenOrder() {
        Member member = member("results-repeat");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        List<ResultView> results = results(member, second, first, second, first);

        assertThat(results).extracting(ResultView::runId).containsExactly(second, first);
    }

    @Test
    void moreThanOneHundredIdsIs400() {
        Member member = member("results-limit");
        List<UUID> hundred = distinctRuns(100);
        List<UUID> tooMany = distinctRuns(101);

        assertThat(member.get(query(hundred), ResultsView.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<ProblemView> refused = member.get(query(tooMany), ProblemView.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody().errors()).containsOnlyKeys("runId");
        for (String path : List.of("/run-results", "/run-results?runId=not-a-uuid")) {
            ResponseEntity<ProblemView> bad = member.get(path, ProblemView.class);

            assertThat(bad.getStatusCode()).as(path).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(bad.getBody().errors()).as(path).containsOnlyKeys("runId");
        }
    }

    @Test
    void oneHundredRunsAreOneStatement() {
        Member member = member("results-cost");
        List<UUID> runs = distinctRuns(100);
        Instant base = Instant.now().minus(Duration.ofHours(1));
        for (int i = 0; i < runs.size(); i++) {
            member.output("image", "image " + i, runs.get(i), base.plusSeconds(i));
        }
        STATEMENTS.clear();

        ResponseEntity<ResultsView> response = member.get(query(runs), ResultsView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().results()).hasSize(100)
                .allSatisfy(result -> assertThat(result.thumbnails()).hasSize(1));
        assertThat(List.copyOf(STATEMENTS)).hasSize(1);
        verify(assetFiles, never()).probe(any(), any());
    }

    private static List<ResultView> results(Member member, UUID... runIds) {
        ResponseEntity<ResultsView> response = member.get(query(List.of(runIds)), ResultsView.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().results();
    }

    private static String query(List<UUID> runIds) {
        StringBuilder path = new StringBuilder("/run-results?");
        for (int i = 0; i < runIds.size(); i++) {
            path.append(i == 0 ? "" : "&").append("runId=").append(runIds.get(i));
        }
        return path.toString();
    }

    private static List<UUID> distinctRuns(int count) {
        List<UUID> runs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            runs.add(UUID.randomUUID());
        }
        return runs;
    }
}
