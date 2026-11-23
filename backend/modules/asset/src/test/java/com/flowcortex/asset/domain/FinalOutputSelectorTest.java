package com.flowcortex.asset.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.asset.domain.FinalOutputSelector.Selection;
import com.flowcortex.asset.domain.FinalOutputSelector.SkippedOutput;
import com.flowcortex.asset.domain.FinalOutputSelector.StepOutputs;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FinalOutputSelectorTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID STEP_2 = UUID.randomUUID();
    private static final UUID STEP_3 = UUID.randomUUID();

    private static OutputCandidate out(UUID step, String key, String mime, boolean inMedia) {
        return new OutputCandidate(
                UUID.randomUUID(), OWNER, step, "other", inMedia ? "media" : "uploads", key, mime, 10L, inMedia);
    }

    @Test
    void theHighestStepWithAMediaOutputWins() {
        OutputCandidate audio = out(STEP_2, "runs/r/steps/2/a.mp3", "audio/mpeg", true);
        OutputCandidate video = out(STEP_3, "runs/r/steps/3/v.mp4", "video/mp4", true);

        Selection selection = FinalOutputSelector.select(
                List.of(new StepOutputs(STEP_3, List.of(video)), new StepOutputs(STEP_2, List.of(audio))));

        assertThat(selection.candidates()).hasSize(1);
        assertThat(selection.candidates().get(0).mediaAssetId()).isEqualTo(video.mediaAssetId());
        assertThat(selection.candidates().get(0).kind()).isEqualTo("video");
        assertThat(selection.skipped()).isEmpty();
    }

    @Test
    void aLaterStepWithOnlyJsonFallsBackToTheEarlierStep() {
        OutputCandidate json = out(STEP_3, "runs/r/steps/3/m.json", "application/json", true);
        OutputCandidate audio = out(STEP_2, "runs/r/steps/2/a.mp3", "audio/mpeg", true);

        Selection selection = FinalOutputSelector.select(
                List.of(new StepOutputs(STEP_3, List.of(json)), new StepOutputs(STEP_2, List.of(audio))));

        assertThat(selection.candidates())
                .extracting(OutputCandidate::mediaAssetId)
                .containsExactly(audio.mediaAssetId());
        assertThat(selection.candidates().get(0).kind()).isEqualTo("audio");
        assertThat(selection.skipped()).containsExactly(new SkippedOutput(json.mediaAssetId(), "unsupported_kind"));
    }

    @Test
    void jsonBesideTheVideoIsSkippedAsUnsupportedKind() {
        OutputCandidate video = out(STEP_3, "runs/r/steps/3/a.mp4", "video/mp4", true);
        OutputCandidate json = out(STEP_3, "runs/r/steps/3/b.json", "application/json", true);

        Selection selection = FinalOutputSelector.select(List.of(new StepOutputs(STEP_3, List.of(video, json))));

        assertThat(selection.candidates())
                .extracting(OutputCandidate::mediaAssetId)
                .containsExactly(video.mediaAssetId());
        assertThat(selection.skipped()).containsExactly(new SkippedOutput(json.mediaAssetId(), "unsupported_kind"));
    }

    @Test
    void outputsOutsideTheMediaBucketNeverCount() {
        OutputCandidate stray = out(STEP_3, "owner/upload.mp4", "video/mp4", false);
        OutputCandidate audio = out(STEP_2, "runs/r/steps/2/a.mp3", "audio/mpeg", true);

        Selection earlier = FinalOutputSelector.select(
                List.of(new StepOutputs(STEP_3, List.of(stray)), new StepOutputs(STEP_2, List.of(audio))));
        assertThat(earlier.candidates())
                .extracting(OutputCandidate::mediaAssetId)
                .containsExactly(audio.mediaAssetId());
        assertThat(earlier.skipped()).isEmpty();

        Selection none = FinalOutputSelector.select(List.of(new StepOutputs(STEP_3, List.of(stray))));
        assertThat(none.candidates()).isEmpty();
        assertThat(none.skipped()).isEmpty();
    }

    @Test
    void noProducingStepYieldsNothing() {
        assertThat(FinalOutputSelector.select(List.of()).candidates()).isEmpty();
        assertThat(FinalOutputSelector.select(List.of(new StepOutputs(STEP_3, List.of()))).candidates())
                .isEmpty();
    }

    @Test
    void candidatesAreInObjectKeyOrder() {
        OutputCandidate b = out(STEP_3, "runs/r/steps/3/b.png", "image/png", true);
        OutputCandidate a = out(STEP_3, "runs/r/steps/3/a.png", "image/png", true);

        Selection selection = FinalOutputSelector.select(List.of(new StepOutputs(STEP_3, List.of(b, a))));

        assertThat(selection.candidates())
                .extracting(OutputCandidate::objectKey)
                .containsExactly("runs/r/steps/3/a.png", "runs/r/steps/3/b.png");
    }
}
