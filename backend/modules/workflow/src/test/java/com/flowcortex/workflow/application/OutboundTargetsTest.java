package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.MediaLimits;
import com.flowcortex.workflow.application.OutboundTargets.Target;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OutboundTargetsTest {

    private static final Map<String, List<String>> FIXTURE = Map.of("fixture.send", List.of("url"));

    private static StepDefinition step(int order, String type, String config) {
        return new StepDefinition(order, null, type, config, null);
    }

    @Test
    void aStringDestinationIsListedVerbatim() {
        List<Target> targets = OutboundTargets.targetsOf(
                List.of(step(3, "fixture.send", "{\"url\":\"https://hooks.example.com/x\"}")), FIXTURE);

        assertThat(targets).containsExactly(new Target(3, "fixture.send", "url", "https://hooks.example.com/x"));
    }

    @Test
    void aNonStringDestinationIsListedAsJsonText() {
        List<Target> targets = OutboundTargets.targetsOf(
                List.of(step(1, "fixture.send", "{\"url\":{\"host\":\"a.example\",\"port\":8080}}")), FIXTURE);

        assertThat(targets).containsExactly(
                new Target(1, "fixture.send", "url", "{\"host\":\"a.example\",\"port\":8080}"));
    }

    @Test
    void aStepWithoutTheFieldIsNotListed() {
        assertThat(OutboundTargets.targetsOf(List.of(step(1, "fixture.send", "{\"other\":1}"),
                step(2, "fixture.send", null), step(3, "fixture.send", "{\"url\":null}")), FIXTURE)).isEmpty();
    }

    @Test
    void aTypeOutsideTheTableIsNotListed() {
        assertThat(OutboundTargets.targetsOf(List.of(step(1, "echo", "{\"url\":\"https://x.example\"}")), FIXTURE))
                .isEmpty();
    }

    @Test
    void theProductionTableNamesOnlyRegisteredTypes() {
        MediaJobs mediaJobs = mock(MediaJobs.class);
        when(mediaJobs.providerLimits(anyString()))
                .thenReturn(new MediaLimits(Duration.ofMinutes(5), Duration.ofMinutes(2), 1000L));
        StepExecutorRegistry registry = new StepExecutorRegistry(mediaJobs, Clock.systemUTC());

        assertThat(registry.knownTypes()).containsAll(OutboundTargets.TABLE.keySet());
    }
}
