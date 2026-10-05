package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.MediaLimits;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The step types the engine knows, and how each one runs. */
class StepExecutorRegistryTest {

    private final MediaJobs mediaJobs = mock(MediaJobs.class);
    private final StepExecutorRegistry registry = new StepExecutorRegistry(mediaJobs, Clock.systemUTC());

    private static StepContext context(String input) {
        return new StepContext(UUID.randomUUID(), UUID.randomUUID(), 1, "echo", (short) 1, input, null);
    }

    @Test
    void theDemoTypesAndEveryMediaCapabilityAreKnown() {
        assertThat(registry.knownTypes()).containsExactlyInAnyOrder("echo", "fail", "probe", "extract_audio");
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

        assertThat(registry.isRemote("probe")).isTrue();
        assertThat(registry.isRemote("extract_audio")).isTrue();
        assertThat(registry.isRemote("echo")).isFalse();
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
    void aRemoteStepsBoundsComeFromTheMediaLimitsOfItsCapability() {
        when(mediaJobs.limitsFor("probe"))
                .thenReturn(new MediaLimits(Duration.ofMinutes(30), Duration.ofMinutes(5), 1000L));
        StepExecutor probe = registry.find("probe").orElseThrow();

        assertThat(probe.maxRuntime()).isEqualTo(Duration.ofMinutes(5));
        assertThat(probe.queueTimeout(Duration.ofSeconds(120))).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void twoExecutorsForTheSameTypeAreRejected() {
        assertThatThrownBy(() -> new StepExecutorRegistry(List.of(new EchoStepExecutor(), new EchoStepExecutor())))
                .isInstanceOf(IllegalStateException.class);
    }
}
