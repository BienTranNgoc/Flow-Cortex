package com.flowcortex.llm.descriptor;

import com.flowcortex.llm.api.ModelDescriptor;
import com.flowcortex.llm.api.ModelDescriptors;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Checks that every model id a configuration names has a descriptor of the expected provider. */
public final class DescriptorCoverage {

    private DescriptorCoverage() {
    }

    /**
     * The problems found, in map order: a property that names no model, an id without a descriptor, and an id
     * described for another provider. A list value may itself be a comma list ({@code "a, b"}); blanks are ignored.
     */
    public static List<String> missing(
            Map<String, List<String>> idsByProperty, ModelDescriptors descriptors, String providerKey) {
        List<String> problems = new ArrayList<>();
        idsByProperty.forEach((property, values) -> {
            List<String> ids = values.stream()
                    .flatMap(value -> Arrays.stream(value.split(",")))
                    .map(String::trim)
                    .filter(id -> !id.isEmpty())
                    .toList();
            if (ids.isEmpty()) {
                problems.add(property + ": no model id is configured");
                return;
            }
            for (String id : ids) {
                Optional<ModelDescriptor> described = descriptors.describe(id);
                if (described.isEmpty()) {
                    problems.add(property + ": " + id + " has no descriptor");
                } else if (!providerKey.equals(described.get().providerKey())) {
                    problems.add(property + ": " + id + " is described for provider "
                            + described.get().providerKey() + ", not " + providerKey);
                }
            }
        });
        return problems;
    }
}
