package com.flowcortex.content.legal;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.content.api.ContentDocumentDefinition;
import com.flowcortex.content.section.DocumentRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class LegalCatalogueTest {

    private static List<ContentDocumentDefinition> beans() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(LegalDocumentsConfig.class)) {
            return context.getBeanProvider(ContentDocumentDefinition.class).orderedStream().toList();
        }
    }

    @Test
    void registersElevenDocumentsAndTheIndexThatTheRegistryAccepts() {
        DocumentRegistry registry = new DocumentRegistry(beans(), List.of(new LegalDocSection(), new LegalIndexSection()));

        List<ContentDocumentDefinition> all = registry.all();

        assertThat(all).extracting(ContentDocumentDefinition::slug).containsExactly(
                "legal-service-conditions", "legal-privacy", "legal-termination-refund", "legal-plan-change-renewal",
                "legal-pricing", "legal-delivery", "legal-payment", "legal-service-provision",
                "legal-rights-obligations", "legal-complaints", "legal-terms", "legal-index");
        assertThat(all.subList(0, 11)).allSatisfy(definition -> {
            assertThat(definition.sectionTypes()).containsExactly("legalDoc");
            assertThat(definition.publicHistory()).isTrue();
        });
        ContentDocumentDefinition index = all.get(11);
        assertThat(index.sectionTypes()).containsExactly("legalIndex");
        assertThat(index.publicHistory()).isFalse();
        assertThat(all).allSatisfy(definition -> {
            assertThat(definition.minSections()).isEqualTo(1);
            assertThat(definition.maxSections()).isEqualTo(1);
        });
    }

    @Test
    void configBeansMatchTheCatalogueInOrder() {
        List<String> beanSlugs = beans().stream().map(ContentDocumentDefinition::slug)
                .filter(slug -> !slug.equals(LegalCatalogue.INDEX_SLUG)).toList();

        assertThat(beanSlugs).containsExactlyElementsOf(LegalCatalogue.slugs());
    }
}
