package com.flowcortex.llm.gemini;

import com.flowcortex.llm.api.LlmClient;
import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class GeminiRegistryConfig {

    @Bean
    GeminiRegisteredProvider geminiRegisteredProvider(GeminiProperties properties, GeminiSlotProperties slots,
            HttpClient geminiHttpClient, LlmClient llmClient) {
        return new GeminiRegisteredProvider(properties, slots,
                GeminiAttempts.restClient(geminiHttpClient, properties.readTimeout()), llmClient);
    }
}
