package com.flowcortex.connector.application.telegram;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code flowcortex.connectors.telegram}: the on/off switch (default off), Telegram's API base URL and the
 * read timeouts of the lookups ({@code getMe}, {@code getUpdates}) and of a send.
 */
@ConfigurationProperties("flowcortex.connectors.telegram")
public record TelegramProperties(boolean enabled, URI apiBaseUrl, Duration readTimeout, Duration sendTimeout) {

    public TelegramProperties {
        apiBaseUrl = apiBaseUrl == null ? URI.create("https://api.telegram.org") : apiBaseUrl;
        readTimeout = readTimeout == null ? Duration.ofSeconds(8) : readTimeout;
        sendTimeout = sendTimeout == null ? Duration.ofSeconds(15) : sendTimeout;
    }
}
