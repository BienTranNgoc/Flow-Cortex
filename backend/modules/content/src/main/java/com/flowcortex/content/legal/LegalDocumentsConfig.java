package com.flowcortex.content.legal;

import com.flowcortex.content.api.ContentDocumentDefinition;
import com.flowcortex.content.api.LocalizedText;
import java.util.List;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the legal documents and the index in every profile, one definition bean each, in catalogue order
 * and the index last. The beans are registered programmatically, not as {@code @Bean} methods: Modulith's
 * observability proxies every bean whose type its scan finds as a {@code @Bean} method of the module, and
 * {@link ContentDocumentDefinition} is a final record.
 */
@Configuration(proxyBeanMethods = false)
class LegalDocumentsConfig implements BeanDefinitionRegistryPostProcessor {

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        for (LegalCatalogue.Entry entry : LegalCatalogue.DOCUMENTS) {
            ContentDocumentDefinition definition = new ContentDocumentDefinition(LegalCatalogue.slugOf(entry.doc()),
                    entry.name(), List.of(LegalDocSection.TYPE), true, 1, 1);
            register(registry, definition);
        }
        register(registry, new ContentDocumentDefinition(LegalCatalogue.INDEX_SLUG,
                new LocalizedText("Chỉ mục tài liệu pháp lý", "Legal documents index"),
                List.of(LegalIndexSection.TYPE), false, 1, 1));
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
    }

    private static void register(BeanDefinitionRegistry registry, ContentDocumentDefinition definition) {
        BeanDefinition bean = BeanDefinitionBuilder
                .genericBeanDefinition(ContentDocumentDefinition.class, () -> definition).getBeanDefinition();
        registry.registerBeanDefinition(definition.slug() + "-document", bean);
    }
}
