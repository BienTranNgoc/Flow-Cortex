package com.flowcortex.app.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.flowcortex.llm.gemini.GeminiSlotProperties;
import com.flowcortex.llm.gemini.GeminiSlotProperties.Slot;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Binds the shipped {@code application.yml} with its defaults, no Spring context: a YAML 1.1 scalar
 * such as an unquoted {@code off} would load as the boolean {@code false} and only show up here.
 */
class ShippedGeminiSlotsTest {

    private static StandardEnvironment environment;

    @BeforeAll
    static void loadShippedConfig() throws IOException {
        environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
    }

    private static GeminiSlotProperties bind() {
        return Binder.get(environment).bind("flowcortex.llm.gemini", GeminiSlotProperties.class).get();
    }

    @Test
    void theShippedSlotsBindAndPassTheirChecks() {
        assertThatCode(ShippedGeminiSlotsTest::bind).doesNotThrowAnyException();

        assertThat(bind().slots()).containsOnlyKeys("dub-translate", "dub-speech", "support-assistant");
    }

    @Test
    void theShippedThinkingIsOffForTranslateAndAssistantAndDefaultForSpeech() {
        List<Slot> slots = List.of(bind().slots().get("dub-translate"), bind().slots().get("support-assistant"),
                bind().slots().get("dub-speech"));

        assertThat(slots).extracting(Slot::thinking).containsExactly("off", "off", null);
    }

    @Test
    void theShippedThresholdsAreTheGlobalDefaultAndOffForTranslate() {
        GeminiSlotProperties properties = bind();

        assertThat(properties.safetyThreshold()).isEqualTo("BLOCK_MEDIUM_AND_ABOVE");
        assertThat(properties.slots().get("dub-translate").safetyThreshold()).isEqualTo("OFF");
        assertThat(properties.slots().get("support-assistant").safetyThreshold()).isNull();
    }

    @Test
    void noSupportSettingNamesAModel() {
        assertThat(environment.containsProperty("flowcortex.support.assistant.model")).isFalse();
    }
}
