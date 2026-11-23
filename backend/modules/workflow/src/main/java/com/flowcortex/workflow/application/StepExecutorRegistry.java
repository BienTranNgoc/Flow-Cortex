package com.flowcortex.workflow.application;

import com.flowcortex.media.api.MediaCapability;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.workflow.api.IterateStepConfig;
import com.flowcortex.workflow.application.vendor.VendorJobLedger;
import com.flowcortex.workflow.application.vendor.VendorJobsProperties;
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
 * types, the dub steps, the template loader, the connector call, the two ingest steps, the link download step,
 * and one remote executor per other media capability. Workflow creation rejects any other type.
 */
@Component
class StepExecutorRegistry {

    private final Map<String, StepExecutor> byType = new LinkedHashMap<>();

    @Autowired
    StepExecutorRegistry(MediaJobs mediaJobs, TranslateStepExecutor translate, TtsStepExecutor tts,
            VendorJobLedger vendorLedger, VendorJobsProperties vendorJobs, WorkflowCallStepExecutor workflowCall,
            LoadTemplateStepExecutor loadTemplate, ConnectorCallStepExecutor connectorCall,
            IterateStepExecutor iterate) {
        this(withNestedSteps(withDubSteps(executors(mediaJobs, Clock.systemUTC(), vendorLedger, vendorJobs), translate,
                tts, loadTemplate), workflowCall, connectorCall, iterate));
    }

    StepExecutorRegistry(MediaJobs mediaJobs, Clock clock) {
        this(executors(mediaJobs, clock, null, null));
    }

    StepExecutorRegistry(List<StepExecutor> executors) {
        for (StepExecutor executor : executors) {
            if (byType.putIfAbsent(executor.type(), executor) != null) {
                throw new IllegalStateException("Two executors for step type " + executor.type());
            }
        }
    }

    private static List<StepExecutor> executors(MediaJobs mediaJobs, Clock clock, VendorJobLedger vendorLedger,
            VendorJobsProperties vendorJobs) {
        List<StepExecutor> executors = new ArrayList<>(List.of(new EchoStepExecutor(), new FailStepExecutor()));
        executors.add(new ProviderStepExecutor("generate_script", "product_ad_v1", mediaJobs, clock));
        executors.add(new ProviderStepExecutor("generate_storyboard", "product_ad_storyboard_v1",
                mediaJobs, clock));
        executors.add(new ProviderStepExecutor("generate_speech", "vi_voice_v1", mediaJobs, clock));
        executors.add(new ProviderStepExecutor("generate_keyframe", "vertical_ad_keyframe_v1", mediaJobs, clock,
                vendorLedger, vendorJobs));
        executors.add(new ProviderStepExecutor("generate_video", "vertical_ad_clip_v1", mediaJobs, clock,
                vendorLedger, vendorJobs));
        executors.add(new ProviderStepExecutor("lipsync_video", "vertical_ad_lipsync_v1", mediaJobs, clock,
                vendorLedger, vendorJobs));
        executors.add(new FetchVideoStepExecutor(mediaJobs, clock));
        executors.add(new IngestStepExecutor("ingest_video", "video/", "video", mediaJobs));
        executors.add(new IngestStepExecutor("ingest_image", "image/", "image", mediaJobs));
        Arrays.stream(MediaCapability.values())
                .filter(capability -> capability != MediaCapability.FETCH_VIDEO)
                .map(capability -> new RemoteStepExecutor(capability.value(), mediaJobs, clock))
                .forEach(executors::add);
        return executors;
    }

    private static List<StepExecutor> withDubSteps(List<StepExecutor> executors, StepExecutor translate,
            StepExecutor tts, StepExecutor loadTemplate) {
        executors.add(translate);
        executors.add(tts);
        executors.add(loadTemplate);
        return executors;
    }

    private static List<StepExecutor> withNestedSteps(List<StepExecutor> executors, StepExecutor workflowCall,
            StepExecutor connectorCall, StepExecutor iterate) {
        executors.add(workflowCall);
        executors.add(connectorCall);
        executors.add(iterate);
        return executors;
    }

    Optional<StepExecutor> find(String type) {
        return Optional.ofNullable(byType.get(type));
    }

    /** An unknown type counts as available: it fails at dispatch with its own message. */
    boolean isAvailable(String type) {
        return find(type).map(StepExecutor::available).orElse(true);
    }

    /** Whether every step type a step of {@code type} runs is available; an iterate step also runs its children's type. */
    boolean isAvailable(String type, String config) {
        return IterateStepConfig.effectiveTypes(type, config).stream().allMatch(this::isAvailable);
    }

    /** The types an iterate step may run per item: the closed list, narrowed to those the engine has registered. */
    Set<String> iterateChildTypes() {
        return IterateStepConfig.CHILD_TYPES.stream().filter(byType::containsKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    boolean isRemote(String type) {
        return find(type).map(executor -> executor.mode() == StepExecutor.Mode.REMOTE).orElse(false);
    }

    /** How the engine feeds a step of this type with this config; an unknown type reads a resolved input. */
    StepExecutor.InputPolicy inputPolicy(String type, String config) {
        return find(type).map(executor -> executor.inputPolicy(config)).orElse(StepExecutor.InputPolicy.RESOLVED);
    }

    /** Whether the step type reads an uploaded file, so a workflow using it must start with a media step. */
    boolean usesStoredInput(String type) {
        return find(type).map(executor -> executor.inputPolicy() != StepExecutor.InputPolicy.RESOLVED).orElse(false);
    }

    /** Whether a Product Video run snapshots its product assets onto this step, whether or not the type needs an upload. */
    boolean snapshotsRunInputs(String type) {
        return find(type).map(StepExecutor::bindsRunInputs).orElse(false);
    }

    /**
     * Whether step {@code index} of a run whose steps have {@code types} gets the upload stored at run
     * start: only when step 1 takes it, and, after an ingest step 1, not for a stored-input step, which
     * reads the file through {@code outputInputs} instead.
     */
    boolean snapshotsUpload(List<String> types, int index) {
        String type = types.get(index);
        if (!snapshotsRunInputs(type)) {
            return false;
        }
        if (index == 0) {
            return true;
        }
        String first = types.getFirst();
        return snapshotsRunInputs(first) && !(ingestTypes().contains(first) && usesStoredInput(type));
    }

    Set<String> knownTypes() {
        return byType.keySet();
    }

    Set<String> remoteTypes() {
        return byType.keySet().stream().filter(this::isRemote).collect(Collectors.toUnmodifiableSet());
    }

    /** The step types that copy an uploaded file into their own output. */
    Set<String> ingestTypes() {
        return byType.values().stream().filter(IngestStepExecutor.class::isInstance).map(StepExecutor::type)
                .collect(Collectors.toUnmodifiableSet());
    }

    Set<String> storedInputTypes() {
        return byType.keySet().stream().filter(this::usesStoredInput).collect(Collectors.toUnmodifiableSet());
    }
}
