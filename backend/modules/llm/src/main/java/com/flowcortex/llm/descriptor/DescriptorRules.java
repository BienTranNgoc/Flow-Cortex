package com.flowcortex.llm.descriptor;

import com.flowcortex.llm.api.ModelDescriptor;
import com.flowcortex.llm.api.ModelDescriptor.Knob;
import com.flowcortex.llm.api.ModelDescriptor.Modality;
import com.flowcortex.llm.api.ModelDescriptor.Status;
import com.flowcortex.llm.api.ModelDescriptor.Thinking;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The rules a descriptor set must keep; every violation names the id it concerns. */
public final class DescriptorRules {

    private DescriptorRules() {
    }

    /** The violations of the set in list order, one entry per broken rule; empty when the set is valid. */
    public static List<String> violations(List<ModelDescriptor> descriptors) {
        Map<String, ModelDescriptor> byId = new HashMap<>();
        for (ModelDescriptor descriptor : descriptors) {
            byId.putIfAbsent(descriptor.id(), descriptor);
        }

        List<String> violations = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<String> reportedDuplicates = new HashSet<>();
        for (ModelDescriptor descriptor : descriptors) {
            if (!seen.add(descriptor.id()) && reportedDuplicates.add(descriptor.id())) {
                violations.add(descriptor.id() + ": more than one descriptor has this id");
            }
            check(descriptor, byId, violations);
        }
        return violations;
    }

    private static void check(ModelDescriptor descriptor, Map<String, ModelDescriptor> byId, List<String> violations) {
        String id = descriptor.id();
        if (!descriptor.inputModalities().contains(Modality.TEXT)) {
            violations.add(id + ": input modalities do not include text");
        }
        if (descriptor.limits().output() > descriptor.limits().context()) {
            violations.add(id + ": output limit " + descriptor.limits().output()
                    + " is above context limit " + descriptor.limits().context());
        }
        checkThinking(id, descriptor.thinking(), violations);
        if (descriptor.status() == Status.DEPRECATED) {
            checkSuccessor(id, descriptor.successorId(), byId, violations);
        }
    }

    private static void checkThinking(String id, Thinking thinking, List<String> violations) {
        if (thinking.knob() == Knob.NONE) {
            if (!thinking.values().isEmpty() || thinking.defaultValue() != null) {
                violations.add(id + ": thinking knob none carries values or a default");
            }
        } else if (thinking.defaultValue() == null || !thinking.values().contains(thinking.defaultValue())) {
            violations.add(id + ": thinking default " + thinking.defaultValue() + " is not one of " + thinking.values());
        }
    }

    private static void checkSuccessor(
            String id, String successorId, Map<String, ModelDescriptor> byId, List<String> violations) {
        if (successorId == null || successorId.isBlank()) {
            violations.add(id + ": deprecated without a successor");
            return;
        }
        ModelDescriptor successor = byId.get(successorId);
        if (successor == null) {
            violations.add(id + ": successor " + successorId + " has no descriptor");
        } else if (successor.status() == Status.DEPRECATED) {
            violations.add(id + ": successor " + successorId + " is deprecated");
        }
    }
}
