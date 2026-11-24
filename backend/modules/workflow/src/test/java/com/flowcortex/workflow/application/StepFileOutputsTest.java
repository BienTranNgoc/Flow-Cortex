package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** {@link StepFileOutputs} against {@code contracts/step-file-outputs.json}, which the worker tests also read. */
class StepFileOutputsTest {

    @Test
    void declarationsEqualTheContract() throws IOException {
        String dir = Objects.requireNonNull(System.getProperty("flowcortex.contracts.dir"),
                "system property flowcortex.contracts.dir is not set (see modules/workflow/build.gradle.kts)");
        JsonNode types = new ObjectMapper().readTree(Files.readString(Path.of(dir, "step-file-outputs.json")))
                .get("types");
        Map<String, Set<String>> contract = new TreeMap<>();
        types.fields().forEachRemaining(entry -> {
            Set<String> names = new TreeSet<>();
            entry.getValue().forEach(name -> names.add(name.asText()));
            contract.put(entry.getKey(), names);
        });

        Set<String> allTypes = new TreeSet<>(contract.keySet());
        allTypes.addAll(StepFileOutputs.all().keySet());
        for (String type : allTypes) {
            assertThat(StepFileOutputs.all().get(type))
                    .as("declared files of '%s' in StepFileOutputs vs contracts/step-file-outputs.json", type)
                    .isEqualTo(contract.get(type));
        }
    }

    @Test
    void alignAudioDeclaresItsTwoFiles() {
        assertThat(StepFileOutputs.declared("align_audio"))
                .contains(Set.of("aligned-speech.wav", "alignment-manifest.json"));
    }

    @Test
    void translateDeclaresNoFiles() {
        assertThat(StepFileOutputs.declared("translate")).contains(Set.of());
    }

    @Test
    void anUnlistedTypeIsUndeclared() {
        assertThat(StepFileOutputs.declared("something.new")).isEqualTo(Optional.empty());
    }
}
