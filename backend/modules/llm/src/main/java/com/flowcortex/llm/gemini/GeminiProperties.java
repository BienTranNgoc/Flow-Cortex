package com.flowcortex.llm.gemini;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gemini API settings. A blank {@code apiKey} still lets the app start; every call then fails as
 * {@code UPSTREAM} and logs a config error. {@code readTimeout} applies to each socket read;
 * {@code answerTimeout} bounds one whole answer.
 */
@ConfigurationProperties("flowcortex.llm.gemini")
public record GeminiProperties(String apiKey, URI baseUrl, Duration connectTimeout, Duration readTimeout,
        Duration answerTimeout) {

    public GeminiProperties {
        if (baseUrl == null) {
            baseUrl = URI.create("https://generativelanguage.googleapis.com/v1beta");
        }
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(5);
        }
        if (readTimeout == null) {
            readTimeout = Duration.ofSeconds(20);
        }
        if (answerTimeout == null) {
            answerTimeout = Duration.ofSeconds(20);
        }
    }

    boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
