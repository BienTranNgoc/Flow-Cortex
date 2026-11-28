package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.workflow.api.ProductVideoWorkflow;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.infrastructure.WorkflowStepRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductVideoPresetCatalogTest {

    private final ProductVideoPresetCatalog catalog = new ProductVideoPresetCatalog();

    @Test
    void templatesAndPresetsAreExposed() {
        assertThat(catalog.templates()).extracting(ProductVideoPresetCatalog.TemplateSpec::id)
                .containsExactly("showroom-v1", "mc-v1");
        assertThat(catalog.mcPresets()).extracting(ProductVideoPresetCatalog.McPreset::id)
                .containsExactly("mc-nu-tre", "mc-nu-thanh-lich", "mc-nam-nang-dong");
        assertThat(catalog.backgroundPresets()).extracting(ProductVideoPresetCatalog.BackgroundPreset::id)
                .containsExactly("bg-studio-minimal", "bg-warm-wood", "bg-luxury-dark", "bg-cafe-modern");
    }

    @Test
    void marksMcTemplateAvailableWhenEnabledAndMcWorkflowHasMcSteps() {
        WorkflowStepRepository steps = mock(WorkflowStepRepository.class);
        when(steps.findByWorkflowIdOrderByStepOrderAsc(ProductVideoWorkflow.ID)).thenReturn(List.of());
        when(steps.findByWorkflowIdOrderByStepOrderAsc(ProductVideoWorkflow.MC_ID)).thenReturn(List.of(
                new WorkflowStep(UUID.randomUUID(), ProductVideoWorkflow.MC_ID, (short) 5, "kf", "generate_keyframe", null),
                new WorkflowStep(UUID.randomUUID(), ProductVideoWorkflow.MC_ID, (short) 8, "ls", "lipsync_video", null)));

        ProductVideoPresetCatalog enabledCatalog = new ProductVideoPresetCatalog(true, steps);
        assertThat(enabledCatalog.templates())
                .filteredOn(t -> "mc-v1".equals(t.id()))
                .singleElement()
                .extracting(ProductVideoPresetCatalog.TemplateSpec::available)
                .isEqualTo(true);
    }

    @Test
    void rejectsUnknownCategoryOrPathTraversal() {
        assertThatThrownBy(() -> catalog.loadPresetImage("mc", "../secret"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> catalog.loadPresetImage("unknown", "mc-nu-tre"))
                .isInstanceOf(NotFoundException.class);
    }
}
