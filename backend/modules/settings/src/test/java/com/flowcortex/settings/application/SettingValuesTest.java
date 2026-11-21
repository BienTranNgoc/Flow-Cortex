package com.flowcortex.settings.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.settings.api.SettingDefinition;
import com.flowcortex.settings.api.SettingType;
import com.flowcortex.settings.application.SettingValues.Accepted;
import com.flowcortex.settings.application.SettingValues.Refused;
import com.flowcortex.settings.application.SettingValues.RequestValue;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SettingValuesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SettingDefinition<Boolean> SWITCH =
            SettingDefinition.switchKey("a.b.enabled", true, () -> null);
    private static final SettingDefinition<Integer> INTEGER =
            SettingDefinition.integerKey("a.b.limit", 1, Integer.MAX_VALUE, () -> 5);

    private static RequestValue request(SettingDefinition<?> definition, String json) {
        JsonNode node = JSON.readTree(json);
        return SettingValues.parseRequest(definition, node);
    }

    private static void assertAccepted(SettingDefinition<?> definition, String json, String canonical) {
        assertThat(request(definition, json)).as(json).isEqualTo(new Accepted(canonical));
    }

    private static void assertRefused(SettingDefinition<?> definition, String json) {
        RequestValue result = request(definition, json);
        assertThat(result).as(json).isInstanceOf(Refused.class);
    }

    @Test
    void aSwitchAcceptsOnlyBooleanNodes() {
        assertAccepted(SWITCH, "true", "true");
        assertAccepted(SWITCH, "false", "false");
        assertRefused(SWITCH, "\"false\"");
        assertRefused(SWITCH, "0");
        assertRefused(SWITCH, "null");
        assertRefused(SWITCH, "[]");
    }

    @Test
    void anIntegerAcceptsOnlyIntegralNodesWithinBounds() {
        assertAccepted(INTEGER, "1", "1");
        assertAccepted(INTEGER, "2147483647", "2147483647");
        assertAccepted(INTEGER, "30", "30");
        assertRefused(INTEGER, "12.5");
        assertRefused(INTEGER, "\"12\"");
        assertRefused(INTEGER, "1e3");
        assertRefused(INTEGER, "0");
        assertRefused(INTEGER, "-3");
        assertRefused(INTEGER, "2147483648");
        assertRefused(INTEGER, "true");
        assertRefused(INTEGER, "null");
    }

    @Test
    void aRefusalNamesTheExpectedTypeAndBounds() {
        assertThat(request(INTEGER, "12.5")).isEqualTo(new Refused(SettingType.INTEGER, 1, Integer.MAX_VALUE));
        assertThat(request(SWITCH, "0")).isEqualTo(new Refused(SettingType.SWITCH, null, null));
    }

    @Test
    void parseStoredReadsTheCanonicalTextOfTheType() {
        assertThat(SettingValues.parseStored(SWITCH, "true")).contains(true);
        assertThat(SettingValues.parseStored(SWITCH, "false")).contains(false);
        assertThat(SettingValues.parseStored(INTEGER, "42")).contains(42);
    }

    @Test
    void parseStoredRefusesTextThatIsNotTheCanonicalFormOfTheType() {
        assertThat(SettingValues.parseStored(INTEGER, "abc")).isEmpty();
        assertThat(SettingValues.parseStored(INTEGER, "1.0")).isEmpty();
        assertThat(SettingValues.parseStored(INTEGER, "007")).isEmpty();
        assertThat(SettingValues.parseStored(INTEGER, "+5")).isEmpty();
        assertThat(SettingValues.parseStored(INTEGER, "99999999999")).isEmpty();
        assertThat(SettingValues.parseStored(INTEGER, "0")).isEmpty();
        assertThat(SettingValues.parseStored(INTEGER, "")).isEmpty();
        assertThat(SettingValues.parseStored(SWITCH, "TRUE ")).isEmpty();
        assertThat(SettingValues.parseStored(SWITCH, "1")).isEmpty();
        assertThat(SettingValues.parseStored(SWITCH, "")).isEmpty();
    }
}
