package com.flowcortex.drive.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.drive.domain.DriveRoles.ContentType;
import com.flowcortex.drive.domain.SuggestionProjector.Suggestion;
import com.flowcortex.workflow.api.RunResultView;
import com.flowcortex.workflow.api.RunResultView.Output;
import com.flowcortex.workflow.api.RunResultView.Step;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The read-only pin candidates drawn from recent completed runs. */
class SuggestionProjectorTest {

    private static final Instant FINISHED = Instant.parse("2026-11-01T10:05:00Z");
    private static final String LONG_INPUT = "This is the text the member typed to start the run, 40+ chars";

    private static Step step(int order, String type, String mode, String output, Output... outputs) {
        return new Step(UUID.randomUUID(), order, type, mode, "completed", output, FINISHED,
                Arrays.asList(outputs));
    }

    private static Output media(String name, String mime) {
        return new Output(name, "flowcortex-media", "runs/r/steps/1/" + name, mime, 100);
    }

    private static RunResultView run(String textInput, Step... steps) {
        return new RunResultView(UUID.randomUUID(), UUID.randomUUID(), "Workflow", textInput, FINISHED,
                Arrays.asList(steps));
    }

    @Test
    void everyCompletedStepContributesNotOnlyTheFinal() {
        Step text = step(1, "echo", "inline", "sixty characters of plain text, long enough to be kept as is");
        Step render = step(2, "render_video", "remote", null, media("final.mp4", "video/mp4"));
        Step running = new Step(UUID.randomUUID(), 3, "render", "remote", "running", null, null,
                List.of(media("late.mp4", "video/mp4")));

        List<Suggestion> list = SuggestionProjector.project(List.of(run(null, text, render, running)), 20);

        assertThat(list).extracting(Suggestion::stepType).containsExactly("render_video", "echo");
        assertThat(list).extracting(Suggestion::kind).containsExactly(ContentType.VIDEO, ContentType.TEXT);
        Suggestion video = list.getFirst();
        assertThat(video.output().name()).isEqualTo("final.mp4");
        assertThat(video.stepOrder()).isEqualTo(2);
        assertThat(video.workflowName()).isEqualTo("Workflow");
        assertThat(video.at()).isEqualTo(FINISHED);
    }

    @Test
    void anInputOf40CharactersIsACandidateAnd39IsNot() {
        assertThat(SuggestionProjector.project(List.of(run("a".repeat(40))), 20)).hasSize(1);
        assertThat(SuggestionProjector.project(List.of(run("a".repeat(39))), 20)).isEmpty();
        assertThat(SuggestionProjector.project(List.of(run("a".repeat(30) + " ".repeat(20))), 20)).isEmpty();
    }

    @Test
    void aRunStartedFromAnUploadOffersNoInput() {
        Step upload = step(1, "transcribe", "remote", null);

        assertThat(SuggestionProjector.project(List.of(run(null, upload)), 20)).isEmpty();
    }

    @Test
    void duplicateIdsKeepTheFirst() {
        Step render = step(1, "render", "remote", null, media("a.png", "image/png"), media("a.png", "image/png"));
        RunResultView once = run(LONG_INPUT, render);

        List<Suggestion> list = SuggestionProjector.project(List.of(once, once), 20);

        assertThat(list).extracting(Suggestion::id).doesNotHaveDuplicates().hasSize(2);
        assertThat(list).extracting(Suggestion::id)
                .containsExactlyInAnyOrder(render.stepId() + "/a.png", once.runId() + "/input");
    }

    @Test
    void roleHintsAreProductPhotoDriverVideoAudioAssetAndAudienceNote() {
        Step render = step(1, "render", "inline", "plain note of enough length to be a text candidate",
                media("a.png", "image/png"), media("b.mp4", "video/mp4"), media("c.mp3", "audio/mpeg"));

        List<Suggestion> list = SuggestionProjector.project(List.of(run(LONG_INPUT, render)), 20);

        assertThat(list).extracting(Suggestion::roleHint)
                .containsExactly(null, "product_photo", "driver_video", "audio_asset", "audience_note");
    }

    @Test
    void previewsCollapseWhitespaceAndCutAt160() {
        Step text = step(1, "echo", "inline", "line one\n\n   line   two\t" + "x".repeat(300));

        Suggestion suggestion = SuggestionProjector.project(List.of(run(null, text)), 20).getFirst();

        assertThat(suggestion.previewText()).startsWith("line one line two x").hasSize(160);
    }

    @Test
    void theListIsCutToTheLimit() {
        Step render = step(1, "render", "remote", null, media("a.png", "image/png"), media("b.png", "image/png"),
                media("c.png", "image/png"));

        assertThat(SuggestionProjector.project(List.of(run(null, render)), 2)).hasSize(2);
        assertThat(SuggestionProjector.project(List.of(), 2)).isEmpty();
    }

    @Test
    void aTextCandidateCarriesItsFullTextAndOneOver32768IsTooLong() {
        String full = "w".repeat(300);
        Step text = step(1, "echo", "inline", full);
        Step huge = step(2, "echo", "inline", "h".repeat(32769));
        Step edge = step(3, "echo", "inline", "e".repeat(32768));
        Step render = step(4, "render", "remote", null, media("a.png", "image/png"));

        List<Suggestion> list = SuggestionProjector.project(List.of(run(null, text, huge, edge, render)), 20);

        Suggestion media = list.get(0);
        assertThat(media.output()).isNotNull();
        assertThat(media.textValue()).isNull();
        assertThat(media.tooLong()).isFalse();
        Suggestion atCap = list.get(1);
        assertThat(atCap.textValue()).hasSize(32768);
        assertThat(atCap.tooLong()).isFalse();
        Suggestion tooLong = list.get(2);
        assertThat(tooLong.textValue()).isNull();
        assertThat(tooLong.tooLong()).isTrue();
        assertThat(tooLong.previewText()).hasSize(160);
        Suggestion kept = list.get(3);
        assertThat(kept.textValue()).isEqualTo(full);
        assertThat(kept.previewText()).hasSize(160);
    }
}
