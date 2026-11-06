package com.flowcortex.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A code missing from a bundle throws inside the exception handler, so every code the backend sources
 * name must exist in English and Vietnamese. Codes are read from {@code src/main} as literals.
 */
class MessageBundleParityTest {

    private static final Pattern LOCALIZED_MESSAGE = Pattern.compile("new LocalizedMessage\\(");
    private static final Pattern LOCALIZED_MESSAGE_CODE = Pattern.compile("new LocalizedMessage\\(\\s*\"([^\"]+)\"");
    private static final Pattern HANDLER_CODE = Pattern.compile("\\b(?:message|getMessage)\\(\\s*\"([^\"]+)\"");

    @Test
    void bothBundlesHoldTheSameKeys() {
        assertThat(bundle("i18n/messages_vi.properties").stringPropertyNames())
                .containsExactlyInAnyOrderElementsOf(bundle("i18n/messages.properties").stringPropertyNames());
    }

    @Test
    void everyCodeTheSourcesNameExistsInBothBundles() {
        Set<String> codes = new TreeSet<>();
        for (String source : sources()) {
            codes.addAll(matches(LOCALIZED_MESSAGE_CODE, source));
            codes.addAll(matches(HANDLER_CODE, source));
        }

        assertThat(codes).contains("workflow.notFound", "problem.validationFailed", "auth.rateLimited.login");
        assertThat(bundle("i18n/messages.properties").stringPropertyNames()).containsAll(codes);
        assertThat(bundle("i18n/messages_vi.properties").stringPropertyNames()).containsAll(codes);
    }

    @Test
    void everyLocalizedMessageNamesItsCodeAsALiteral() {
        for (String source : sources()) {
            assertThat(matches(LOCALIZED_MESSAGE_CODE, source)).hasSize(count(LOCALIZED_MESSAGE, source));
        }
    }

    private static List<String> sources() {
        Path backend = Path.of(System.getProperty("flowcortex.backend.dir"));
        List<String> sources = new ArrayList<>();
        for (String group : List.of("modules", "shared")) {
            try (Stream<Path> projects = Files.list(backend.resolve(group))) {
                for (Path project : projects.toList()) {
                    Path main = project.resolve("src/main/java");
                    if (Files.isDirectory(main)) {
                        try (Stream<Path> files = Files.walk(main)) {
                            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                                sources.add(Files.readString(file));
                            }
                        }
                    }
                }
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
        assertThat(sources).isNotEmpty();
        return sources;
    }

    private static List<String> matches(Pattern pattern, String source) {
        List<String> found = new ArrayList<>();
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    private static int count(Pattern pattern, String source) {
        return (int) pattern.matcher(source).results().count();
    }

    private static Properties bundle(String resource) {
        Properties properties = new Properties();
        try (InputStream in = MessageBundleParityTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return properties;
    }
}
