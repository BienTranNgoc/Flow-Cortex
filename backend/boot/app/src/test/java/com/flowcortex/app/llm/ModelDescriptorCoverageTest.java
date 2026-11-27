package com.flowcortex.app.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.llm.descriptor.DescriptorCoverage;
import com.flowcortex.llm.descriptor.SeededModelDescriptors;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Every text model the shipped default configuration names has a descriptor, with no Spring context. The
 * defaults come from {@code application.yml} alone: the system environment is left out so an exported
 * {@code FLOWCORTEX_*} variable cannot change what is checked.
 */
class ModelDescriptorCoverageTest {

    private static final String PROVIDER = "gemini";
    private static final String CHAT_MODEL = "flowcortex.chat.model";
    private static final String ASSISTANT_MODEL = "flowcortex.support.assistant.model";
    private static final String SLOTS = "flowcortex.llm.gemini.slots";
    private static final String ASSISTANT_SLOT_MODELS = SLOTS + ".support-assistant.models";
    private static final Pattern SLOT_MODELS = Pattern.compile("^" + Pattern.quote(SLOTS) + "\\.([^.]+)\\.models$");
    private static final Set<String> SPEECH_SLOTS = Set.of("dub-speech");

    // gemini-3.5-flash has no value sheet that states its thinking values and temperature, and the chat
    // default is not this change's to move; the exemption is checked below so it cannot outlive that default.
    private static final String EXEMPT_PROPERTY = CHAT_MODEL;
    private static final String EXEMPT_ID = "gemini-3.5-flash";

    private static Map<String, String> shipped;

    @BeforeAll
    static void loadShippedConfig() throws IOException {
        shipped = new TreeMap<>();
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))) {
            for (String name : ((EnumerablePropertySource<?>) source).getPropertyNames()) {
                shipped.putIfAbsent(name, String.valueOf(source.getProperty(name)));
            }
        }
    }

    private static Map<String, String> shippedWith(String property, String value) {
        Map<String, String> config = new HashMap<>(shipped);
        config.put(property, value);
        return config;
    }

    private static Map<String, String> shippedWithout(String... properties) {
        Map<String, String> config = new HashMap<>(shipped);
        for (String property : properties) {
            config.remove(property);
        }
        return config;
    }

    /** The coverage problems of a configuration; empty when every property the check reads is described. */
    private static List<String> problems(Map<String, String> config) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addLast(new MapPropertySource("coverage", new HashMap<>(config)));

        List<String> problems = new ArrayList<>();
        Map<String, List<String>> idsByProperty = new LinkedHashMap<>();

        int slotCount = 0;
        for (String name : new TreeMap<>(config).keySet()) {
            Matcher slot = SLOT_MODELS.matcher(name);
            if (!slot.matches()) {
                continue;
            }
            slotCount++;
            if (!SPEECH_SLOTS.contains(slot.group(1))) {
                idsByProperty.put(name, List.of(environment.getProperty(name, "")));
            }
        }
        if (slotCount == 0) {
            problems.add(SLOTS + ": no slot is configured");
        }

        if (config.containsKey(ASSISTANT_MODEL)) {
            idsByProperty.put(ASSISTANT_MODEL, List.of(environment.getProperty(ASSISTANT_MODEL, "")));
        } else if (!config.containsKey(ASSISTANT_SLOT_MODELS)) {
            problems.add(ASSISTANT_MODEL + ": the assistant model is at neither this property nor " + ASSISTANT_SLOT_MODELS);
        }

        if (!config.containsKey(CHAT_MODEL)) {
            problems.add(CHAT_MODEL + ": the property is absent");
        } else {
            String chat = environment.getProperty(CHAT_MODEL, "");
            if (!EXEMPT_ID.equals(chat.trim())) {
                problems.add("exemption (" + EXEMPT_PROPERTY + ", " + EXEMPT_ID + ") no longer matches the configured "
                        + chat.trim() + ": remove the exemption");
                idsByProperty.put(CHAT_MODEL, List.of(chat));
            }
        }

        problems.addAll(DescriptorCoverage.missing(idsByProperty, new SeededModelDescriptors(), PROVIDER));
        return problems;
    }

    @Test
    void theShippedDefaultsAreCoveredWithTheChatModelAsTheOneExemption() {
        assertThat(problems(shipped)).isEmpty();
    }

    @Test
    void aStaleExemptionFailsNamingIt() {
        List<String> problems = problems(shippedWith(CHAT_MODEL, "gemini-3.8-flash"));

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains(EXEMPT_PROPERTY).contains(EXEMPT_ID).contains("remove the exemption");
    }

    @Test
    void aConfiguredModelWithoutADescriptorNamesThePropertyAndTheId() {
        List<String> problems = problems(shippedWith(CHAT_MODEL, "gemini-9-flash"));

        assertThat(problems).anySatisfy(problem -> assertThat(problem).contains(CHAT_MODEL).contains("gemini-9-flash")
                .contains("has no descriptor"));
    }

    @Test
    void aNewTextSlotWithAnUndescribedModelNamesTheSlotPropertyAndTheId() {
        String property = SLOTS + ".support-assistant.models";

        List<String> problems = problems(shippedWith(property, "gemini-3.5-flash-lite,gemini-9-flash"));

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains(property).contains("gemini-9-flash");
    }

    @Test
    void aSlotAddedLaterIsCheckedWithoutAnEditHere() {
        String property = SLOTS + ".brand-new.models";

        assertThat(problems(shippedWith(property, "gemini-3.8-flash"))).isEmpty();
        assertThat(problems(shippedWith(property, "gemini-9-flash"))).anySatisfy(
                problem -> assertThat(problem).contains(property).contains("gemini-9-flash"));
    }

    @Test
    void theAssistantModelInASlotPassesWhenThePropertyIsAbsent() {
        assertThat(shipped).doesNotContainKey(ASSISTANT_MODEL).containsKey(ASSISTANT_SLOT_MODELS);

        assertThat(problems(shippedWithout(ASSISTANT_MODEL))).isEmpty();
    }

    @Test
    void theAssistantModelAtItsPropertyIsCheckedWhenThePropertyExists() {
        List<String> problems = problems(shippedWith(ASSISTANT_MODEL, "gemini-9-flash"));

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains(ASSISTANT_MODEL).contains("gemini-9-flash");
    }

    @Test
    void anAssistantModelAtNeitherLocationFailsNamingTheProperty() {
        List<String> problems = problems(shippedWithout(ASSISTANT_SLOT_MODELS));

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains(ASSISTANT_MODEL).contains(ASSISTANT_SLOT_MODELS);
    }

    @Test
    void anAbsentChatModelFailsNamingTheProperty() {
        List<String> problems = problems(shippedWithout(CHAT_MODEL));

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains(CHAT_MODEL).contains("absent");
    }

    @Test
    void noSlotAtAllFailsNamingTheSlotsProperty() {
        Map<String, String> noSlots = new HashMap<>(shipped);
        noSlots.keySet().removeIf(name -> SLOT_MODELS.matcher(name).matches());

        List<String> problems = problems(noSlots);

        assertThat(problems).anySatisfy(problem -> assertThat(problem).contains(SLOTS));
    }

    @Test
    void theSpeechSlotIsNotRead() {
        String speech = SLOTS + ".dub-speech.models";
        assertThat(shipped).containsKey(speech);

        assertThat(problems(shippedWith(speech, "gemini-9-tts"))).isEmpty();
    }
}
