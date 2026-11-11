package com.flowcortex.drive.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.drive.domain.DriveRoles.ContentType;
import com.flowcortex.drive.domain.MemoryProjector.Entry;
import com.flowcortex.workflow.api.RunResultView;
import com.flowcortex.workflow.api.RunResultView.Output;
import com.flowcortex.workflow.api.RunResultView.Step;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Which results of a completed run reach Memory, and how each entry is keyed and sized. */
class MemoryProjectorTest {

    private static final Instant FINISHED = Instant.parse("2026-11-01T10:05:00Z");
    private static final Instant STEP_FINISHED = Instant.parse("2026-11-01T10:04:00Z");

    private static Step step(int order, String type, String mode, String status, String output, Output... outputs) {
        return new Step(UUID.randomUUID(), order, type, mode, status, output, STEP_FINISHED,
                Arrays.asList(outputs));
    }

    private static Step echo(int order, String text) {
        return step(order, "echo", "inline", "completed", text);
    }

    private static Output media(String name, String mime, long bytes) {
        return new Output(name, "flowcortex-media", "runs/r/steps/1/" + name, mime, bytes);
    }

    private static RunResultView run(String workflowName, Step... steps) {
        return new RunResultView(UUID.randomUUID(), UUID.randomUUID(), workflowName, "input", FINISHED,
                Arrays.asList(steps));
    }

    @Test
    void theHighestCompletedStepWithACandidateWins() {
        Step translate = step(1, "translate", "inline", "completed", "{\"segments\":[]}");
        Step first = echo(2, "an early text");
        Step render = step(3, "render_video", "remote", "completed", null, media("final.mp4", "video/mp4", 900));
        Step open = step(4, "render_video", "remote", "running", null, media("late.mp4", "video/mp4", 10));

        List<Entry> entries = MemoryProjector.project(run("Dub", translate, first, render, open));

        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().kind()).isEqualTo(ContentType.VIDEO);
        assertThat(entries.getFirst().stepId()).isEqualTo(render.stepId());
        assertThat(entries.getFirst().fileName()).isEqualTo("final.mp4");
        assertThat(entries.getFirst().resultAt()).isEqualTo(STEP_FINISHED);
    }

    @Test
    void aFinalStepWithoutCandidatesFallsBackToTheStepBefore() {
        Step images = step(1, "render_images", "remote", "completed", null,
                media("a.png", "image/png", 10), media("b.png", "image/png", 20));
        Step empty = step(2, "translate", "inline", "completed", "{\"segments\":[]}");

        List<Entry> entries = MemoryProjector.project(run("Poster", images, empty));

        assertThat(entries).extracting(Entry::fileName).containsExactly("a.png", "b.png");
        assertThat(entries).extracting(Entry::stepId).containsOnly(images.stepId());
    }

    @Test
    void remoteOutputTextIsNeverAResult() {
        Step remote = step(1, "generate_script", "remote", "completed", "A plain script nobody extracts");

        assertThat(MemoryProjector.project(run("Script", remote))).isEmpty();
    }

    @Test
    void jsonObjectOrArrayOutputIsNotText() {
        assertThat(MemoryProjector.project(run("W", echo(1, "{\"a\":1}")))).isEmpty();
        assertThat(MemoryProjector.project(run("W", echo(1, "  [1, 2]  ")))).isEmpty();
        assertThat(MemoryProjector.project(run("W", echo(1, "   ")))).isEmpty();
        assertThat(MemoryProjector.project(run("W", echo(1, null)))).isEmpty();
        assertThat(MemoryProjector.project(run("W", echo(1, "{not json")))).hasSize(1);
        assertThat(MemoryProjector.project(run("W", echo(1, "\"quoted\"")))).hasSize(1);
        assertThat(MemoryProjector.project(run("W", echo(1, "{\"a\":1} trailing")))).hasSize(1);
    }

    @Test
    void plainInlineTextIsOneEntryWithA500CharacterPreview() {
        String text = "word ".repeat(200);

        List<Entry> entries = MemoryProjector.project(run("Notes", echo(1, text)));

        assertThat(entries).hasSize(1);
        Entry entry = entries.getFirst();
        assertThat(entry.kind()).isEqualTo(ContentType.TEXT);
        assertThat(entry.previewText()).isEqualTo(text.substring(0, 500));
        assertThat(entry.bucket()).isNull();
        assertThat(entry.objectKey()).isNull();
        assertThat(entry.fileName()).isNull();
        assertThat(entry.mimeType()).isNull();
    }

    @Test
    void eachImageVideoAudioOutputIsAnEntryAndOtherMimesAreSkipped() {
        Step render = step(1, "render", "remote", "completed", null,
                media("a.png", "image/png", 1), media("b.mp4", "video/mp4", 2), media("c.mp3", "audio/mpeg", 3),
                media("d.json", "application/json", 4), media("e.txt", "text/plain", 5),
                media("f.bin", null, 6));

        List<Entry> entries = MemoryProjector.project(run("Mix", render));

        assertThat(entries).extracting(Entry::kind)
                .containsExactly(ContentType.IMAGE, ContentType.VIDEO, ContentType.AUDIO);
        assertThat(entries).extracting(Entry::sizeBytes).containsExactly(1L, 2L, 3L);
        assertThat(entries.getFirst().bucket()).isEqualTo("flowcortex-media");
        assertThat(entries.getFirst().objectKey()).isEqualTo("runs/r/steps/1/a.png");
        assertThat(entries.getFirst().mimeType()).isEqualTo("image/png");
        assertThat(entries.getFirst().previewText()).isNull();
    }

    @Test
    void resultKeysAreStepIdAndStepIdSlashName() {
        Step text = echo(1, "hello");
        Step render = step(2, "render", "remote", "completed", null, media("final.mp4", "video/mp4", 2),
                media("final.mp4", "video/mp4", 2));

        assertThat(MemoryProjector.project(run("W", text)).getFirst().resultKey()).isEqualTo(text.stepId().toString());
        List<Entry> media = MemoryProjector.project(run("W", text, render));
        assertThat(media).extracting(Entry::resultKey).containsExactly(render.stepId() + "/final.mp4");
    }

    @Test
    void textSizeIsItsUtf8ByteLength() {
        Entry entry = MemoryProjector.project(run("W", echo(1, "ééé"))).getFirst();

        assertThat(entry.sizeBytes()).isEqualTo(6L);
    }

    @Test
    void titleIsTheWorkflowNameCutTo200() {
        assertThat(MemoryProjector.project(run("Dub", echo(1, "x"))).getFirst().title()).isEqualTo("Dub");
        assertThat(MemoryProjector.project(run("n".repeat(250), echo(1, "x"))).getFirst().title())
                .isEqualTo("n".repeat(200));
    }

    @Test
    void aFinalGenerateScriptStepYieldsNoTextEntry() {
        Step script = step(2, "generate_script", "remote", "completed", "{\"script\":\"Hello\"}");
        Step input = step(1, "echo", "inline", "completed", "   ");

        assertThat(MemoryProjector.project(run("Script", input, script))).isEmpty();
    }
}
