package com.flowcortex.llm.descriptor;

import com.flowcortex.llm.api.ModelDescriptor;
import com.flowcortex.llm.api.ModelDescriptor.Knob;
import com.flowcortex.llm.api.ModelDescriptor.Limits;
import com.flowcortex.llm.api.ModelDescriptor.Modality;
import com.flowcortex.llm.api.ModelDescriptor.Status;
import com.flowcortex.llm.api.ModelDescriptor.StructuredOutput;
import com.flowcortex.llm.api.ModelDescriptor.Thinking;
import com.flowcortex.llm.api.ModelDescriptors;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The shipped model descriptors, each value as the vendor's pages state it; a value a page
 * leaves unstated is not filled in. Pages per model: {@code https://ai.google.dev/gemini-api/docs/models/<id>}
 * plus {@code /docs/deprecations} and {@code /docs/thinking}; the thinking and sampling cells also cite
 * {@code /docs/whats-new-gemini-3.6} (3.5 Flash-Lite) and {@code /docs/whats-new-gemini-3.8} (3.8 Flash).
 * The set is checked on construction, so an invalid edit stops the application from starting.
 */
@Component
public class SeededModelDescriptors implements ModelDescriptors {

    private static final String PROVIDER = "gemini";
    private static final List<Modality> INPUTS =
            List.of(Modality.TEXT, Modality.IMAGE, Modality.VIDEO, Modality.AUDIO, Modality.PDF);
    private static final Limits LIMITS = new Limits(1_048_576, 65_536);

    private final List<ModelDescriptor> descriptors;

    public SeededModelDescriptors() {
        List<ModelDescriptor> seeded = List.of(flashLite35(), flash38()).stream()
                .sorted(Comparator.comparing(ModelDescriptor::id))
                .toList();
        List<String> violations = DescriptorRules.violations(seeded);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Invalid model descriptors: " + String.join("; ", violations));
        }
        this.descriptors = seeded;
    }

    @Override
    public Optional<ModelDescriptor> describe(String id) {
        return descriptors.stream().filter(descriptor -> descriptor.id().equals(id)).findFirst();
    }

    @Override
    public List<ModelDescriptor> all() {
        return descriptors;
    }

    /** Thinking {@code level}; the page's temperature note says the sampling parameters are ignored. */
    private static ModelDescriptor flashLite35() {
        return new ModelDescriptor(
                "gemini-3.5-flash-lite", PROVIDER, "flash-lite", INPUTS, LIMITS, true,
                StructuredOutput.RESPONSE_SCHEMA,
                new Thinking(Knob.LEVEL, List.of("minimal", "low", "medium", "high"), "minimal"),
                false, Status.STABLE, null, null, "gemini-3.5-flash-lite:text");
    }

    /** Thinking {@code level}; {@code minimal} is rejected by the vendor, so it is not an admissible value. */
    private static ModelDescriptor flash38() {
        return new ModelDescriptor(
                "gemini-3.8-flash", PROVIDER, "flash", INPUTS, LIMITS, true,
                StructuredOutput.RESPONSE_SCHEMA,
                new Thinking(Knob.LEVEL, List.of("low", "medium", "high"), "medium"),
                false, Status.STABLE, null, null, "gemini-3.8-flash:text");
    }
}
