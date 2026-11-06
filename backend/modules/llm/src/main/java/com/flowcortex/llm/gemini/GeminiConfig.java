package com.flowcortex.llm.gemini;

import com.flowcortex.llm.api.LlmClient;
import com.flowcortex.llm.api.LlmTimeouts;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GeminiProperties.class)
class GeminiConfig {

    @Bean
    LlmTimeouts llmTimeouts(GeminiProperties properties) {
        return properties::answerTimeout;
    }

    @Bean
    LlmClient llmClient(GeminiProperties properties, MeterRegistry meters) {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(properties.connectTimeout());
        requests.setReadTimeout(properties.readTimeout());
        return new GeminiLlmClient(properties, RestClient.builder().requestFactory(requests).build(), meters);
    }
}
