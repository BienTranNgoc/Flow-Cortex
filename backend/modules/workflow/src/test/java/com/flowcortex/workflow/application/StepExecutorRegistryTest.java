package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.media.api.MediaJob;
import com.flowcortex.media.api.MediaJobInput;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.MediaLimits;
import com.flowcortex.workflow.api.NewWorkflowStep;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The step types the engine knows, and how each one runs. */
class StepExecutorRegistryTest {

    private final MediaJobs mediaJobs = mock(MediaJobs.class);

    private final StepExecutorRegistry registry = new StepExecutorRegistry(mediaJobs, Clock.systemUTC());

    StepExecutorRegistryTest() {
        when(mediaJobs.providerLimits(anyString()))
                .thenReturn(new MediaLimits(Duration.ofMinutes(5), Duration.ofMinutes(2), 1000L));
    }

    private static StepContext context(String input) {
        return new StepContext(UUID.randomUUID(), UUID.randomUUID(), 1, "echo", (short) 1, input, null, null);
    }

    @Test
    void theDemoTypesAndEveryMediaCapabilityAreKnown() {
        assertThat(registry.knownTypes())
                .containsExactlyInAnyOrder("echo", "fail", "generate_script", "generate_storyboard",
                        "generate_speech", "generate_video", "probe", "extract_audio", "align_audio",
                        "render_video", "separate_audio", "fetch_video", "ingest_video", "ingest_image");
    }

    @Test
    void echoAndFailRunInlineAndMediaCapabilitiesRunRemotely() {
        assertThat(registry.find("echo"))
                .get()
                .extracting(StepExecutor::mode)
                .isEqualTo(StepExecutor.Mode.INLINE);
        assertThat(registry.find("fail"))
                .get()
                .extracting(StepExecutor::mode)
                .isEqualTo(StepExecutor.Mode.INLINE);
        assertThat(registry.find("probe"))
                .get()
                .extracting(StepExecutor::mode)
                .isEqualTo(StepExecutor.Mode.REMOTE);
        assertThat(registry.find("extract_audio"))
                .get()
                .extracting(StepExecutor::mode)
                .isEqualTo(StepExecutor.Mode.REMOTE);
        assertThat(registry.find("generate_script"))
                .get()
                .extracting(StepExecutor::mode)
                .isEqualTo(StepExecutor.Mode.REMOTE);
        assertThat(registry.find("generate_storyboard"))
                .get()
                .extracting(StepExecutor::mode)
                .isEqualTo(StepExecutor.Mode.REMOTE);

        assertThat(registry.isRemote("probe")).isTrue();
        assertThat(registry.isRemote("extract_audio")).isTrue();
        assertThat(registry.isRemote("echo")).isFalse();
        assertThat(registry.usesStoredInput("render_video")).isTrue();
        assertThat(registry.usesStoredInput("generate_script")).isFalse();
        assertThat(registry.usesStoredInput("generate_video")).isFalse();
        assertThat(registry.snapshotsRunInputs("render_video")).isTrue();
        assertThat(registry.snapshotsRunInputs("generate_script")).isTrue();
        assertThat(registry.snapshotsRunInputs("generate_storyboard")).isTrue();
        assertThat(registry.snapshotsRunInputs("generate_video")).isTrue();
        assertThat(registry.snapshotsRunInputs("generate_speech")).isFalse();
    }

    @Test
    void fetchVideoIsRemoteTakesNoStoredInputAndSnapshotsNoUpload() {
        assertThat(registry.find("fetch_video")).get().isInstanceOf(FetchVideoStepExecutor.class);
        assertThat(registry.isRemote("fetch_video")).isTrue();
        assertThat(registry.usesStoredInput("fetch_video")).isFalse();
        assertThat(registry.snapshotsRunInputs("fetch_video")).isFalse();
        assertThat(registry.remoteTypes()).contains("fetch_video");
        assertThat(registry.storedInputTypes()).doesNotContain("fetch_video");
    }

    @Test
    void theIngestTypesAreInlineStoredInputStepsAndExactlyTwo() {
        for (String type : List.of("ingest_video", "ingest_image")) {
            assertThat(registry.find(type)).get().isInstanceOf(IngestStepExecutor.class);
            assertThat(registry.isRemote(type)).isFalse();
            assertThat(registry.usesStoredInput(type)).isTrue();
            assertThat(registry.find(type).orElseThrow().mode()).isEqualTo(StepExecutor.Mode.INLINE);
        }
        assertThat(registry.ingestTypes()).containsExactlyInAnyOrder("ingest_video", "ingest_image");
        assertThat(registry.storedInputTypes()).contains("ingest_video", "ingest_image");
    }

    @Test
    void anUploadIsSnapshottedOnlyOntoTheStepsThatReadIt() {
        assertThat(snapshots("ingest_video", "probe")).containsExactly(true, false);
        assertThat(snapshots("ingest_video", "generate_script")).containsExactly(true, true);
        assertThat(snapshots("ingest_image", "extract_audio")).containsExactly(true, false);
        assertThat(snapshots("probe", "extract_audio")).containsExactly(true, true);
        assertThat(snapshots("fetch_video", "probe")).containsExactly(false, false);
        assertThat(snapshots("generate_script")).containsExactly(true);
        assertThat(snapshots("generate_speech")).containsExactly(false);
        assertThat(snapshots("echo", "generate_script")).containsExactly(false, false);
    }

    private List<Boolean> snapshots(String... types) {
        List<String> list = List.of(types);
        return IntStream.range(0, list.size()).mapToObj(index -> registry.snapshotsUpload(list, index)).toList();
    }

    @Test
    void anUnknownTypeHasNoExecutor() {
        assertThat(registry.find("nope")).isEmpty();
        assertThat(registry.isRemote("nope")).isFalse();
    }

    @Test
    void echoReturnsItsInputVerbatimIncludingNull() {
        StepExecutor echo = registry.find("echo").orElseThrow();

        assertThat(echo.execute(context("hello world"))).isEqualTo("hello world");
        assertThat(echo.execute(context(null))).isNull();
    }

    @Test
    void failAlwaysThrowsAndIsBoundedToSixtySeconds() {
        StepExecutor fail = registry.find("fail").orElseThrow();

        assertThatThrownBy(() -> fail.execute(context("anything")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Simulated step failure");
        assertThat(fail.maxRuntime()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void inlineStepsWaitForPickupNoLongerThanTheLease() {
        assertThat(registry.find("echo").orElseThrow().queueTimeout(Duration.ofSeconds(120)))
                .isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void aProviderStepsBoundsComeFromTheProviderLimitsNotAMediaCapability() {
        StepExecutor script = registry.find("generate_script").orElseThrow();

        assertThat(script.maxRuntime()).isEqualTo(Duration.ofMinutes(2));
        assertThat(script.queueTimeout(Duration.ofSeconds(120))).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void aRemoteStepsBoundsComeFromTheMediaLimitsOfItsCapability() {
        when(mediaJobs.limitsFor("probe"))
                .thenReturn(new MediaLimits(Duration.ofMinutes(30), Duration.ofMinutes(5), 1000L));
        StepExecutor probe = registry.find("probe").orElseThrow();

        assertThat(probe.maxRuntime()).isEqualTo(Duration.ofMinutes(5));
        assertThat(probe.queueTimeout(Duration.ofSeconds(120))).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void aRemoteStepPreservesAnOrderedInputArray() {
        when(mediaJobs.isEnabled()).thenReturn(true);
        when(mediaJobs.limitsFor("render_video"))
                .thenReturn(new MediaLimits(Duration.ofMinutes(30), Duration.ofMinutes(10), 1000L));
        StepExecutor render = registry.find("render_video").orElseThrow();
        String inputs = "[{\"key\":\"image_1\",\"bucket\":\"uploads\",\"objectKey\":\"a\"},"
                + "{\"key\":\"image_2\",\"bucket\":\"uploads\",\"objectKey\":\"b\"}]";

        render.execute(context(inputs));

        ArgumentCaptor<MediaJob> job = ArgumentCaptor.forClass(MediaJob.class);
        verify(mediaJobs).enqueue(job.capture());
        assertThat(job.getValue().inputs()).containsExactly(
                new MediaJobInput("image_1", "uploads", "a"),
                new MediaJobInput("image_2", "uploads", "b"));
    }

    @Test
    void iterateChildTypesAreTheClosedListThatIsRegistered() {
        StepExecutorRegistry full = new StepExecutorRegistry(List.of(inline("echo", true), inline("fail", true),
                inline("translate", true), inline("tts", true), inline("workflow.call", true)));

        assertThat(full.iterateChildTypes()).containsExactlyInAnyOrder("echo", "fail", "translate", "tts");
        assertThat(registry.iterateChildTypes()).containsExactlyInAnyOrder("echo", "fail");
    }

    @Test
    void registeredInlineResolvedTypeOutsideTheListIsNotAChildType() {
        StepExecutorRegistry withExtra = new StepExecutorRegistry(List.of(inline("echo", true),
                inline("inline.extra", true)));

        assertThat(withExtra.knownTypes()).contains("inline.extra");
        assertThat(withExtra.iterateChildTypes()).containsExactly("echo");

        NewWorkflowStep iterate = new NewWorkflowStep("b", "iterate", null,
                "{\"child\":{\"type\":\"inline.extra\"}}");
        var definition = new KnownStepTypes.Definition(List.of(iterate),
                Stream.concat(withExtra.knownTypes().stream(), Stream.of("iterate")).collect(Collectors.toSet()),
                Set.of(), Set.of(), withExtra.ingestTypes(), withExtra.iterateChildTypes());
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Set<ConstraintViolation<KnownStepTypes.Definition>> violations = factory.getValidator().validate(definition);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("steps[0].config");
            assertThat(violations.iterator().next().getMessage()).contains("child.type");
        }
    }

    @Test
    void isAvailableOfIterateIsItsChildTypes() {
        StepExecutorRegistry dubOff = new StepExecutorRegistry(List.of(inline("echo", true),
                inline("translate", false)));
        String echoChild = "{\"child\":{\"type\":\"echo\"}}";
        String translateChild = "{\"child\":{\"type\":\"translate\"}}";

        assertThat(dubOff.isAvailable("iterate", echoChild)).isTrue();
        assertThat(dubOff.isAvailable("iterate", translateChild)).isFalse();
        assertThat(dubOff.isAvailable("translate", null)).isFalse();
        assertThat(dubOff.isAvailable("echo", echoChild)).isTrue();
    }

    @Test
    void anUnreadableIterateConfigIsAvailableLikeTheTypeAlone() {
        StepExecutorRegistry dubOff = new StepExecutorRegistry(List.of(inline("echo", true),
                inline("translate", false)));

        assertThat(dubOff.isAvailable("iterate", null)).isTrue();
        assertThat(dubOff.isAvailable("iterate", "{")).isTrue();
    }

    private static StepExecutor inline(String type, boolean available) {
        return new StepExecutor() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public Mode mode() {
                return Mode.INLINE;
            }

            @Override
            public Duration maxRuntime() {
                return Duration.ofSeconds(60);
            }

            @Override
            public boolean available() {
                return available;
            }

            @Override
            public String execute(StepContext context) {
                return context.input();
            }
        };
    }

    @Test
    void twoExecutorsForTheSameTypeAreRejected() {
        assertThatThrownBy(() -> new StepExecutorRegistry(List.of(new EchoStepExecutor(), new EchoStepExecutor())))
                .isInstanceOf(IllegalStateException.class);
    }
}
