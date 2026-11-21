package com.flowcortex.mail.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.mail.api.MailKey;
import com.flowcortex.shared.web.LocalizedMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

/** Every catalog key must read well in both languages before it can be sent. */
class MailKeyCatalogTest {

    private static final Locale ENGLISH = Locale.ENGLISH;
    private static final Locale VIETNAMESE = Locale.of("vi");
    private static final String LINK = "https://app.example/confirm-email?token=abc";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)");
    private static final Pattern UNREPLACED = Pattern.compile("\\{\\d");

    /** Values typed as the senders pass them: a number wherever the text formats one. */
    private static final Map<MailKey, List<Object>> SAMPLES = new EnumMap<>(Map.of(
            MailKey.ACTIVATION, List.of(LINK, 24),
            MailKey.ACTIVATION_AFTER_RESET_REQUEST, List.of(LINK, 24),
            MailKey.PASSWORD_SETUP_LINK, List.of(LINK, 30),
            MailKey.PASSWORD_SET, List.of(),
            MailKey.RESET_PASSWORD, List.of(LINK, new LocalizedMessage("email.duration.hours", 1)),
            MailKey.PASSWORD_CHANGED, List.of("https://app.example/login"),
            MailKey.GOOGLE_SIGN_IN_NOTICE, List.of("https://app.example/login"),
            MailKey.SUPPORT_REQUEST_REPLIED, List.of(42, "https://app.example/?help=request-42")));

    private final ResourceBundleMessageSource messages = messageSource();

    @Test
    void everyKeyRendersItsSubjectAndBodyInEnglishAndVietnamese() {
        assertThat(SAMPLES.keySet()).as("a sample row for every key").containsExactlyInAnyOrder(MailKey.values());
        Properties english = bundle("i18n/messages.properties");
        Properties vietnamese = bundle("i18n/messages_vi.properties");

        for (MailKey key : MailKey.values()) {
            assertThat(SAMPLES.get(key)).as("%s sample values", key.id()).hasSize(key.valueCount());
            for (Map.Entry<Locale, Properties> language : Map.of(ENGLISH, english, VIETNAMESE, vietnamese).entrySet()) {
                String name = language.getKey().getLanguage();
                Properties bundle = language.getValue();
                assertThat(bundle.stringPropertyNames()).as("%s subject code in %s", key.id(), name)
                        .contains(key.subjectCode());
                assertThat(bundle.stringPropertyNames()).as("%s body code in %s", key.id(), name)
                        .contains(key.bodyCode());

                String subject = render(key.subjectCode(), key, language.getKey());
                String body = render(key.bodyCode(), key, language.getKey());
                assertThat(subject).as("%s subject in %s", key.id(), name).isNotBlank();
                assertThat(UNREPLACED.matcher(subject).find())
                        .as("%s subject in %s has an unreplaced value", key.id(), name).isFalse();
                assertThat(UNREPLACED.matcher(body).find()).as("%s body in %s has an unreplaced value", key.id(), name)
                        .isFalse();

                Set<Integer> used = indexesIn(bundle.getProperty(key.subjectCode()));
                used.addAll(indexesIn(bundle.getProperty(key.bodyCode())));
                Set<Integer> declared = new HashSet<>();
                for (int index = 0; index < key.valueCount(); index++) {
                    declared.add(index);
                }
                assertThat(used).as("%s value indexes used in %s", key.id(), name).isEqualTo(declared);
            }
        }
    }

    @Test
    void wireIdsAreUniqueAndKebabCase() {
        Set<String> ids = new HashSet<>();
        for (MailKey key : MailKey.values()) {
            assertThat(key.id()).matches("[a-z]+(-[a-z]+)*");
            assertThat(ids.add(key.id())).as("duplicate id %s", key.id()).isTrue();
            assertThat(MailKey.fromId(key.id())).contains(key);
        }
        assertThat(MailKey.fromId("no-such-mail")).isEmpty();
    }

    private String render(String code, MailKey key, Locale locale) {
        return messages.getMessage(code, SAMPLES.get(key).toArray(), locale);
    }

    private static Set<Integer> indexesIn(String pattern) {
        Set<Integer> indexes = new HashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(pattern);
        while (matcher.find()) {
            indexes.add(Integer.parseInt(matcher.group(1)));
        }
        return indexes;
    }

    private static ResourceBundleMessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false);
        source.setUseCodeAsDefaultMessage(false);
        return source;
    }

    private static Properties bundle(String path) {
        try (InputStream in = MailKeyCatalogTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as(path).isNotNull();
            Properties properties = new Properties();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return properties;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
