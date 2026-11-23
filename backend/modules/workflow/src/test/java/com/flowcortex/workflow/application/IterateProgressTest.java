package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.workflow.api.IterateStepConfig;
import com.flowcortex.workflow.application.IterateProgress.ChildState;
import com.flowcortex.workflow.application.IterateProgress.Complete;
import com.flowcortex.workflow.application.IterateProgress.Decision;
import com.flowcortex.workflow.application.IterateProgress.Dispatch;
import com.flowcortex.workflow.application.IterateProgress.Fail;
import com.flowcortex.workflow.application.IterateProgress.Wait;
import com.flowcortex.workflow.domain.RunStepStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IterateProgressTest {

    private static final IterateStepConfig CONTINUE = IterateStepConfig.parse("{\"child\":{\"type\":\"echo\"}}");
    private static final IterateStepConfig FAIL_FAST =
            IterateStepConfig.parse("{\"child\":{\"type\":\"echo\"},\"onItemError\":\"fail\"}");

    private static UUID id(int index) {
        return new UUID(0, index);
    }

    private static ChildState child(int index, RunStepStatus status) {
        return new ChildState(id(index), index, status, null, null, null, null);
    }

    private static ChildState done(int index, String output) {
        return new ChildState(id(index), index, RunStepStatus.COMPLETED, output, null, null, null);
    }

    private static ChildState doneWithFiles(int index, String output, String outputs) {
        return new ChildState(id(index), index, RunStepStatus.COMPLETED, output, outputs, null, null);
    }

    private static ChildState failed(int index, String code, String message) {
        return new ChildState(id(index), index, RunStepStatus.ERROR, null, null, code, message);
    }

    private static Decision decide(IterateStepConfig config, int concurrency, ChildState... children) {
        return IterateProgress.decide(config, List.of(children), 0, concurrency);
    }

    private static String file(int item, int attempt) {
        return "{\"name\":\"item-" + item + "-" + attempt + "-v.wav\",\"bucket\":\"b\",\"objectKey\":\"k" + item + "\","
                + "\"mime\":\"audio/wav\",\"bytes\":3}";
    }

    @Test
    void unpaidChildFailsWithInsufficientCreditsEvenWhenContinuing() {
        Decision decision = decide(CONTINUE, 3, done(0, "a"), failed(1, "INSUFFICIENT_CREDITS", "no credits"),
                child(2, RunStepStatus.PENDING));

        assertThat(decision).isEqualTo(new Fail("INSUFFICIENT_CREDITS", "Not enough credits to run this step"));
    }

    @Test
    void unpaidChildBeatsAFailFastItemFailure() {
        Decision decision = decide(FAIL_FAST, 3, failed(0, "boom", "x"), failed(1, "INSUFFICIENT_CREDITS", "no credits"));

        assertThat(decision).isEqualTo(new Fail("INSUFFICIENT_CREDITS", "Not enough credits to run this step"));
    }

    @Test
    void failFastFailsNamingTheFirstFailedItem() {
        Decision decision = decide(FAIL_FAST, 3, done(0, "a"), failed(1, "boom", "It broke"),
                failed(2, "other", "Later"), child(3, RunStepStatus.PENDING));

        assertThat(decision).isInstanceOf(Fail.class);
        Fail fail = (Fail) decision;
        assertThat(fail.code()).isEqualTo("iterate_item_failed");
        assertThat(fail.message()).contains("1").contains("It broke").doesNotContain("Later");
    }

    @Test
    void failFastNamesTheCodeWhenTheChildHasNoMessage() {
        Fail fail = (Fail) decide(FAIL_FAST, 3, failed(0, "provider_busy", null));

        assertThat(fail.message()).contains("0").contains("provider_busy");
    }

    @Test
    void continueIgnoresFailedChildren() {
        Decision decision = decide(CONTINUE, 3, failed(0, "boom", "x"), child(1, RunStepStatus.PENDING));

        assertThat(decision).isEqualTo(new Dispatch(List.of(id(1))));
    }

    @Test
    void allTerminalCompletesWithGatheredOutput() {
        Decision decision = decide(CONTINUE, 3, done(0, "{\"ok\":1}"), failed(1, "boom", "x"), done(2, "done"));

        assertThat(decision).isEqualTo(new Complete(
                "{\"results\":[{\"ok\":1},null,\"done\"],\"texts\":[\"done\"],\"succeeded\":2,\"failed\":1,"
                        + "\"total\":3,\"dropped\":0}", null));
    }

    @Test
    void emptyChildrenCompleteWithZeroTotals() {
        Decision decision = IterateProgress.decide(CONTINUE, List.of(), 0, 3);

        assertThat(decision).isEqualTo(new Complete(
                "{\"results\":[],\"texts\":[],\"succeeded\":0,\"failed\":0,\"total\":0,\"dropped\":0}", null));
    }

    @Test
    void dispatchesNextPendingInItemOrderUpToTheCap() {
        List<ChildState> children = new ArrayList<>(List.of(child(4, RunStepStatus.PENDING),
                child(2, RunStepStatus.PENDING), child(0, RunStepStatus.COMPLETED), child(3, RunStepStatus.PENDING),
                child(1, RunStepStatus.RUNNING)));

        assertThat(IterateProgress.decide(CONTINUE, children, 0, 3)).isEqualTo(new Dispatch(List.of(id(2), id(3))));
        assertThat(IterateProgress.decide(CONTINUE, children, 0, 2)).isEqualTo(new Dispatch(List.of(id(2))));
    }

    @Test
    void dispatchUsesTheEffectiveConcurrency() {
        IterateStepConfig ten = IterateStepConfig.parse("{\"child\":{\"type\":\"tts\"},\"concurrency\":10}");

        Decision decision = decide(ten, 1, child(0, RunStepStatus.PENDING), child(1, RunStepStatus.PENDING),
                child(2, RunStepStatus.PENDING));

        assertThat(decision).isEqualTo(new Dispatch(List.of(id(0))));
    }

    @Test
    void waitsWhenTheCapIsFull() {
        assertThat(decide(CONTINUE, 2, child(0, RunStepStatus.RUNNING), child(1, RunStepStatus.RUNNING),
                child(2, RunStepStatus.PENDING))).isEqualTo(new Wait());
    }

    @Test
    void waitsForTheLastRunningChild() {
        assertThat(decide(CONTINUE, 3, done(0, "a"), child(1, RunStepStatus.RUNNING))).isEqualTo(new Wait());
    }

    @Test
    void skippedChildrenCountAsNotCompletedNorFailed() {
        Decision decision = decide(FAIL_FAST, 3, done(0, "a"), child(1, RunStepStatus.SKIPPED));

        assertThat(decision).isInstanceOf(Complete.class);
        assertThat(((Complete) decision).output()).startsWith("{\"results\":[\"a\",null],\"texts\":[\"a\"],\"succeeded\":1");
        assertThat(decide(CONTINUE, 3, child(0, RunStepStatus.SKIPPED), child(1, RunStepStatus.PENDING)))
                .isEqualTo(new Dispatch(List.of(id(1))));
    }

    @Test
    void anEffectiveConcurrencyBelowOneIsRefused() {
        assertThatThrownBy(() -> IterateProgress.decide(CONTINUE, List.of(), 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void gatherAggregatesStoredOutputsInItemOrder() {
        Complete gathered = IterateProgress.gather(List.of(
                doneWithFiles(1, "b", "[" + file(1, 1) + "]"),
                failed(2, "boom", "x"),
                doneWithFiles(0, "a", "[" + file(0, 1) + "," + file(0, 1).replace("-v.wav", "-w.wav") + "]")), 0);

        assertThat(gathered.outputs()).isEqualTo("[" + file(0, 1) + "," + file(0, 1).replace("-v.wav", "-w.wav") + ","
                + file(1, 1) + "]");
        assertThat(gathered.output()).startsWith("{\"results\":[\"a\",\"b\",null]");
    }

    @Test
    void gatherWithNoStoredOutputsHasNone() {
        assertThat(IterateProgress.gather(List.of(done(0, "a")), 0).outputs()).isNull();
    }

    @Test
    void gatherWritesEmptyTextForACompletedChildWithNoOutput() {
        Complete gathered = IterateProgress.gather(List.of(done(0, null), done(1, "")), 0);

        assertThat(gathered.output()).isEqualTo(
                "{\"results\":[\"\",\"\"],\"texts\":[\"\",\"\"],\"succeeded\":2,\"failed\":0,\"total\":2,\"dropped\":0}");
    }

    @Test
    void gatherKeepsTheDroppedCountAndReadsJsonOutputsByTheirType() {
        Complete gathered = IterateProgress.gather(List.of(done(0, "42"), done(1, "[1]"), done(2, "null"),
                done(3, "1 apple")), 5);

        assertThat(gathered.output()).isEqualTo("{\"results\":[42,[1],\"null\",\"1 apple\"],"
                + "\"texts\":[\"null\",\"1 apple\"],\"succeeded\":4,\"failed\":0,\"total\":4,\"dropped\":5}");
    }

    @Test
    void regatherReplacesOnlyTheRegeneratedSlotAndItsOutputs() {
        String output = "{\"results\":[\"a\",\"b\",{\"c\":1}],\"texts\":[\"a\",\"b\"],\"succeeded\":3,\"failed\":0,"
                + "\"total\":3,\"dropped\":2}";
        String outputs = "[" + file(0, 1) + "," + file(1, 1) + "," + file(2, 1) + "]";

        Complete regathered = IterateProgress.regather(output, outputs, doneWithFiles(1, "B", "[" + file(1, 2) + "]"));

        assertThat(regathered.output()).isEqualTo("{\"results\":[\"a\",\"B\",{\"c\":1}],\"texts\":[\"a\",\"B\"],"
                + "\"succeeded\":3,\"failed\":0,\"total\":3,\"dropped\":2}");
        assertThat(regathered.outputs()).isEqualTo("[" + file(0, 1) + "," + file(1, 2) + "," + file(2, 1) + "]");
    }

    @Test
    void regatherDoesNotConfuseItemOneWithItemTen() {
        String output = "{\"results\":[\"a\",\"b\"],\"texts\":[\"a\",\"b\"],\"succeeded\":2,\"failed\":0,"
                + "\"total\":2,\"dropped\":0}";
        String outputs = "[" + file(1, 1) + "," + file(10, 1) + "]";

        Complete regathered = IterateProgress.regather(output, outputs, done(1, "B"));

        assertThat(regathered.outputs()).isEqualTo("[" + file(10, 1) + "]");
    }

    @Test
    void regatherFromNoStoredOutputsTakesTheChildsFiles() {
        String output = "{\"results\":[\"a\"],\"texts\":[\"a\"],\"succeeded\":1,\"failed\":0,\"total\":1,\"dropped\":0}";

        Complete regathered = IterateProgress.regather(output, null, doneWithFiles(0, "a", "[" + file(0, 2) + "]"));

        assertThat(regathered.outputs()).isEqualTo("[" + file(0, 2) + "]");
    }

    @Test
    void regatherRecomputesTextsAndCounts() {
        String output = "{\"results\":[\"a\",null],\"texts\":[\"a\"],\"succeeded\":1,\"failed\":1,\"total\":2,\"dropped\":0}";

        Complete regathered = IterateProgress.regather(output, null, done(1, "b"));

        assertThat(regathered.output()).isEqualTo(
                "{\"results\":[\"a\",\"b\"],\"texts\":[\"a\",\"b\"],\"succeeded\":2,\"failed\":0,\"total\":2,\"dropped\":0}");
    }

    @Test
    void regatherRefusesAChildThatDidNotComplete() {
        String output = "{\"results\":[\"a\"],\"texts\":[\"a\"],\"succeeded\":1,\"failed\":0,\"total\":1,\"dropped\":0}";

        assertThatThrownBy(() -> IterateProgress.regather(output, null, failed(0, "boom", "x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IterateProgress.regather(output, null, done(1, "x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IterateProgress.regather("not json", null, done(0, "x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deadlineIsRoundsTimesQueuePlusRuntime() {
        Duration deadline = IterateProgress.deadline(7, 3, Duration.ofSeconds(120), Duration.ofSeconds(60),
                Duration.ofSeconds(120));

        assertThat(deadline).isEqualTo(Duration.ofSeconds(540));
    }

    @Test
    void deadlineOfZeroItemsIsTheLease() {
        assertThat(IterateProgress.deadline(0, 3, Duration.ofSeconds(120), Duration.ofSeconds(60),
                Duration.ofSeconds(90))).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void deadlineRoundsUpAPartialRound() {
        assertThat(IterateProgress.deadline(1, 10, Duration.ofSeconds(10), Duration.ofSeconds(5),
                Duration.ofSeconds(90))).isEqualTo(Duration.ofSeconds(15));
    }
}
