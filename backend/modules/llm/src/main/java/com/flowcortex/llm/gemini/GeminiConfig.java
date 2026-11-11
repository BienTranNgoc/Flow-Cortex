package com.flowcortex.llm.gemini;

import com.flowcortex.llm.api.LlmChatClient;
import com.flowcortex.llm.api.LlmClient;
import com.flowcortex.llm.api.LlmSlots;
import com.flowcortex.llm.api.LlmTimeouts;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.net.http.HttpClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({GeminiProperties.class, GeminiSlotProperties.class})
class GeminiConfig {

    @Bean
    LlmTimeouts llmTimeouts(GeminiProperties properties) {
        return properties::answerTimeout;
    }

    /** The one HTTP client of every JSON, speech and chat-stream call; the JVM proxy settings apply to it. */
    @Bean
    HttpClient geminiHttpClient(GeminiProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Bean
    LlmClient llmClient(GeminiProperties properties, HttpClient geminiHttpClient, MeterRegistry meters,
            ObjectProvider<ObservationRegistry> observations) {
        return new GeminiLlmClient(properties, GeminiAttempts.restClient(geminiHttpClient, properties.readTimeout()),
                meters, observations.getIfAvailable(() -> ObservationRegistry.NOOP));
    }

    @Bean
    LlmSlots llmSlots(GeminiProperties properties, GeminiSlotProperties slots, HttpClient geminiHttpClient,
            MeterRegistry meters, ObjectProvider<ObservationRegistry> observations) {
        GeminiTelemetry telemetry = new GeminiTelemetry(meters,
                observations.getIfAvailable(() -> ObservationRegistry.NOOP));
        return new GeminiChainClient(properties, slots,
                callTimeout -> GeminiAttempts.restClient(geminiHttpClient, callTimeout), Thread::sleep, telemetry);
    }

    @Bean
    LlmChatClient llmChatClient(GeminiProperties properties, HttpClient geminiHttpClient, MeterRegistry meters) {
        return new GeminiChatClient(properties, geminiHttpClient, meters);
    }
}
