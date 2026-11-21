package com.flowcortex.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.io.ClassPathResource;

class NotificationTextTest {

    private static final Locale VI = Locale.forLanguageTag("vi");

    private final NotificationText text = new NotificationText(bundle("i18n/messages", "i18n/test-notification"));

    private static ResourceBundleMessageSource bundle(String... basenames) {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasenames(basenames);
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }

    @Test
    void vietnameseReaderGetsVietnameseText() {
        assertThat(text.title("notification.welcome.title", List.of(), VI)).isEqualTo("Chào mừng bạn đến với Nodi");
        assertThat(text.title("notification.welcome.title", List.of(), Locale.ENGLISH)).isEqualTo("Welcome to Nodi");
    }

    @Test
    void aCodeMissingInVietnameseFallsBackToEnglish() {
        assertThat(text.title("notification.test.englishOnly", List.of(), VI)).isEqualTo("English only text");
    }

    @Test
    void aCodeMissingEverywhereRendersTheGenericTitleAndNoBody() {
        assertThat(text.title("notification.nowhere", List.of(), VI)).isEqualTo("Thông báo mới");
        assertThat(text.title("notification.nowhere", List.of(), Locale.ENGLISH)).isEqualTo("New notification");
        assertThat(text.body("notification.nowhere", List.of(), VI)).isEmpty();
        assertThat(text.body(null, List.of(), VI)).isEmpty();
    }

    @Test
    void argumentsAreFormattedIntoTheText() {
        assertThat(text.title("notification.test.hello", List.of("Mai"), Locale.ENGLISH)).isEqualTo("Hello Mai");
        assertThat(text.title("notification.test.hello", List.of("Mai"), VI)).isEqualTo("Xin chào Mai");
    }

    @Test
    void aNumberArgumentWithAnExplicitPatternIsNotGrouped() {
        assertThat(text.title("notification.test.number", List.of(1234), Locale.ENGLISH)).isEqualTo("Order 1234");
        assertThat(text.title("notification.test.number", List.of(1234), VI)).isEqualTo("Đơn 1234");
    }

    @Test
    void everyNotificationCodeHasEnglishAndVietnameseText() throws IOException {
        Set<String> english = notificationKeys("i18n/messages.properties");
        Set<String> vietnamese = notificationKeys("i18n/messages_vi.properties");

        assertThat(english).isNotEmpty();
        assertThat(english).isEqualTo(vietnamese);
    }

    private static Set<String> notificationKeys(String path) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        Set<String> keys = new TreeSet<>();
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith("notification.")) {
                keys.add(key);
            }
        }
        return keys;
    }
}
