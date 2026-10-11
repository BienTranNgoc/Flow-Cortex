package com.flowcortex.workflow.application;

import com.flowcortex.media.api.MediaCapability;
import com.flowcortex.media.api.MediaJobs;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Every step type the engine can run, listed here rather than discovered: the inline demo
 * types plus one remote executor per media capability. Workflow creation rejects any other type.
 */
@Component
class StepExecutorRegistry {

    private final Map<String, StepExecutor> byType = new LinkedHashMap<>();

    @Autowired
    StepExecutorRegistry(MediaJobs mediaJobs) {
        this(mediaJobs, Clock.systemUTC());
    }

    StepExecutorRegistry(MediaJobs mediaJobs, Clock clock) {
        this(executors(mediaJobs, clock));
    }

    StepExecutorRegistry(List<StepExecutor> executors) {
        for (StepExecutor executor : executors) {
            if (byType.putIfAbsent(executor.type(), executor) != null) {
                throw new IllegalStateException("Two executors for step type " + executor.type());
            }
        }
    }

    private static List<StepExecutor> executors(MediaJobs mediaJobs, Clock clock) {
        List<StepExecutor> executors = new ArrayList<>(List.of(new EchoStepExecutor(), new FailStepExecutor()));
        executors.add(new ProviderStepExecutor("generate_script", "product_ad_v1", mediaJobs, clock,
                java.time.Duration.ofMinutes(2), java.time.Duration.ofMinutes(5)));
        executors.add(new ProviderStepExecutor("generate_speech", "vi_voice_v1", mediaJobs, clock,
                java.time.Duration.ofMinutes(2), java.time.Duration.ofMinutes(5)));
        Arrays.stream(MediaCapability.values())
                .map(capability -> new RemoteStepExecutor(capability.value(), mediaJobs, clock))
                .forEach(executors::add);
        return executors;
    }

    Optional<StepExecutor> find(String type) {
        return Optional.ofNullable(byType.get(type));
    }

    boolean isRemote(String type) {
        return find(type).map(executor -> executor.mode() == StepExecutor.Mode.REMOTE).orElse(false);
    }

    boolean usesStoredInput(String type) {
        return find(type).map(executor -> executor.inputPolicy() == StepExecutor.InputPolicy.STORED).orElse(false);
    }

    Set<String> knownTypes() {
        return byType.keySet();
    }

    Set<String> remoteTypes() {
        return byType.keySet().stream().filter(this::isRemote).collect(Collectors.toUnmodifiableSet());
    }

    Set<String> storedInputTypes() {
        return byType.keySet().stream().filter(this::usesStoredInput).collect(Collectors.toUnmodifiableSet());
    }
}
