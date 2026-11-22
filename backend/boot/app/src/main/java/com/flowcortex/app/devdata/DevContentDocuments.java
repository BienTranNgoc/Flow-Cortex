package com.flowcortex.app.devdata;

import com.flowcortex.content.api.ContentDocumentDefinition;
import com.flowcortex.content.api.LocalizedText;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Registers a sample document under the {@code dev} profile only, so the editor, publication and history
 * flows can be tried in a running stack before any real document is registered.
 */
@Configuration
@Profile("dev")
class DevContentDocuments {

    @Bean
    ContentDocumentDefinition devSampleDocument() {
        return new ContentDocumentDefinition("dev-sample", new LocalizedText("Tài liệu mẫu", "Sample document"),
                List.of("richText", "media"), true);
    }
}
