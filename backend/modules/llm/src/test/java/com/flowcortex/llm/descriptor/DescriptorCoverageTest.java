package com.flowcortex.llm.descriptor;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.llm.api.ModelDescriptor;
import com.flowcortex.llm.api.ModelDescriptor.Knob;
import com.flowcortex.llm.api.ModelDescriptor.Limits;
import com.flowcortex.llm.api.ModelDescriptor.Modality;
import com.flowcortex.llm.api.ModelDescriptor.Status;
import com.flowcortex.llm.api.ModelDescriptor.StructuredOutput;
import com.flowcortex.llm.api.ModelDescriptor.Thinking;
import com.flowcortex.llm.api.ModelDescriptors;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DescriptorCoverageTest {

    private static ModelDescriptor descriptor(String id, String providerKey) {
        return new ModelDescriptor(
                id, providerKey, "flash", List.of(Modality.TEXT), new Limits(8192, 4096), true,
                StructuredOutput.NONE, new Thinking(Knob.NONE, List.of(), null), true, Status.STABLE, null, null, null);
    }

    private static ModelDescriptors described(ModelDescriptor... descriptors) {
        List<ModelDescriptor> all = List.of(descriptors);
        return new ModelDescriptors() {
            @Override
            public Optional<ModelDescriptor> describe(String id) {
                return all.stream().filter(d -> d.id().equals(id)).findFirst();
            }

            @Override
            public List<ModelDescriptor> all() {
                return all;
            }
        };
    }

    private static final ModelDescriptors KNOWN = described(descriptor("flash", "gemini"), descriptor("flash-lite", "gemini"));

    @Test
    void everyConfiguredIdDescribedIsEmpty() {
        Map<String, List<String>> ids = new LinkedHashMap<>();
        ids.put("flowcortex.chat.model", List.of("flash"));
        ids.put("flowcortex.llm.gemini.slots.dub-translate.models", List.of("flash-lite", "flash"));

        assertThat(DescriptorCoverage.missing(ids, KNOWN, "gemini")).isEmpty();
    }

    @Test
    void anUndescribedIdNamesThePropertyAndTheId() {
        List<String> missing = DescriptorCoverage.missing(Map.of("flowcortex.chat.model", List.of("gemini-9-flash")), KNOWN, "gemini");

        assertThat(missing).hasSize(1);
        assertThat(missing.get(0)).contains("flowcortex.chat.model").contains("gemini-9-flash");
    }

    @Test
    void aDescribedIdOfAnotherProviderIsReported() {
        ModelDescriptors other = described(descriptor("flash", "other"));

        List<String> missing = DescriptorCoverage.missing(Map.of("flowcortex.chat.model", List.of("flash")), other, "gemini");

        assertThat(missing).hasSize(1);
        assertThat(missing.get(0)).contains("flowcortex.chat.model").contains("flash").contains("other");
    }

    @Test
    void aCommaListIsSplitAndTrimmed() {
        Map<String, List<String>> ids = Map.of("slot.models", List.of(" flash-lite , flash ,gemini-9-flash "));

        List<String> missing = DescriptorCoverage.missing(ids, KNOWN, "gemini");

        assertThat(missing).hasSize(1);
        assertThat(missing.get(0)).contains("slot.models").contains("gemini-9-flash");
    }

    @Test
    void aPropertyMappedToNothingIsReportedByName() {
        List<String> missing = DescriptorCoverage.missing(Map.of("flowcortex.chat.model", List.of()), KNOWN, "gemini");

        assertThat(missing).hasSize(1);
        assertThat(missing.get(0)).contains("flowcortex.chat.model");
    }

    @Test
    void aBlankValueCountsAsNothingConfigured() {
        List<String> missing = DescriptorCoverage.missing(Map.of("flowcortex.chat.model", List.of(" , ")), KNOWN, "gemini");

        assertThat(missing).hasSize(1);
        assertThat(missing.get(0)).contains("flowcortex.chat.model");
    }

    @Test
    void anEmptyMapHasNothingToCheck() {
        assertThat(DescriptorCoverage.missing(Map.of(), KNOWN, "gemini")).isEmpty();
    }

    @Test
    void theSeededSetHasNoViolationsAndIsExactlyTheTwoSeededIdsSortedById() {
        SeededModelDescriptors seeded = new SeededModelDescriptors();

        assertThat(DescriptorRules.violations(seeded.all())).isEmpty();
        assertThat(seeded.all()).extracting(ModelDescriptor::id)
                .containsExactly("gemini-3.5-flash-lite", "gemini-3.8-flash");
    }

    @Test
    void aSeededModelIsDescribedForGeminiWithTextInputAndOutputWithinContext() {
        ModelDescriptor flash = new SeededModelDescriptors().describe("gemini-3.8-flash").orElseThrow();

        assertThat(flash.providerKey()).isEqualTo("gemini");
        assertThat(flash.inputModalities()).contains(Modality.TEXT);
        assertThat(flash.limits().output()).isLessThanOrEqualTo(flash.limits().context());
    }

    @Test
    void anUnknownIdIsNotDescribedAndRaisesNothing() {
        assertThat(new SeededModelDescriptors().describe("gpt-5")).isEmpty();
    }

    @Test
    void everyBrokenPropertyIsReportedInMapOrder() {
        Map<String, List<String>> ids = new LinkedHashMap<>();
        ids.put("first.property", List.of("nope"));
        ids.put("second.property", List.of());

        List<String> missing = DescriptorCoverage.missing(ids, KNOWN, "gemini");

        assertThat(missing).hasSize(2);
        assertThat(missing.get(0)).contains("first.property");
        assertThat(missing.get(1)).contains("second.property");
    }
}
