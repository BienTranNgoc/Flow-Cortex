package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.shared.web.NotFoundException;
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
    void rejectsUnknownCategoryOrPathTraversal() {
        assertThatThrownBy(() -> catalog.loadPresetImage("mc", "../secret"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> catalog.loadPresetImage("unknown", "mc-nu-tre"))
                .isInstanceOf(NotFoundException.class);
    }
}
