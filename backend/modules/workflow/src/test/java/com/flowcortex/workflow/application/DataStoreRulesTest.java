package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowcortex.workflow.application.DataStoreRules.Fault;
import org.junit.jupiter.api.Test;

class DataStoreRulesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNode OK_VALUE = MAPPER.createObjectNode().put("tone", "friendly");

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    private static Fault check(String key, String type, JsonNode value, boolean exists, long count) {
        return DataStoreRules.check(key, type, value, exists, count);
    }

    private static JsonNode stringOfCompactSize(int bytes) {
        return JsonNodeFactory.instance.textNode("a".repeat(bytes - 2));
    }

    @Test
    void validKeysPass() {
        assertThat(check("a", null, OK_VALUE, false, 0)).isNull();
        assertThat(check("a".repeat(100), null, OK_VALUE, false, 0)).isNull();
        assertThat(check(".a", null, OK_VALUE, false, 0)).isNull();
        assertThat(check("a..b", null, OK_VALUE, false, 0)).isNull();
        assertThat(check("A_b-1.2", null, OK_VALUE, false, 0)).isNull();
    }

    @Test
    void invalidKeysAreRefusedUnderTheKeyField() {
        for (String key : new String[] {"", "a".repeat(101), "a b", "a/b", "ä", ".", "..", null}) {
            Fault fault = check(key, null, OK_VALUE, false, 0);
            assertThat(fault).as("key %s", key).isNotNull();
            assertThat(fault.code()).isEqualTo("DATA_STORE_KEY_INVALID");
            assertThat(fault.field()).isEqualTo("key");
            assertThat(fault.message().code()).isEqualTo("workflow.dataStore.keyInvalid");
        }
    }

    @Test
    void aNewlineInAKeyIsRefused() {
        assertThat(check("a\n", null, OK_VALUE, false, 0).code()).isEqualTo("DATA_STORE_KEY_INVALID");
    }

    @Test
    void validTypesPass() {
        assertThat(check("k", null, OK_VALUE, false, 0)).isNull();
        assertThat(check("k", "template", OK_VALUE, false, 0)).isNull();
        assertThat(check("k", "a".repeat(32), OK_VALUE, false, 0)).isNull();
        assertThat(check("k", "my_type-1", OK_VALUE, false, 0)).isNull();
    }

    @Test
    void invalidTypesAreRefusedUnderTheDataTypeField() {
        for (String type : new String[] {"Template", "", "a".repeat(33), "a b"}) {
            Fault fault = check("k", type, OK_VALUE, false, 0);
            assertThat(fault).as("type %s", type).isNotNull();
            assertThat(fault.code()).isEqualTo("DATA_STORE_TYPE_INVALID");
            assertThat(fault.field()).isEqualTo("dataType");
            assertThat(fault.message().code()).isEqualTo("workflow.dataStore.typeInvalid");
        }
    }

    @Test
    void sizeIsTheCompactUtf8Length() throws Exception {
        assertThat(DataStoreRules.sizeOf(json("{\"tone\":\"friendly\"}"))).isEqualTo(19);
        assertThat(DataStoreRules.sizeOf(json("{\n  \"tone\" : \"friendly\"\n}"))).isEqualTo(19);
        assertThat(DataStoreRules.sizeOf(json("\"é\""))).isEqualTo(4);
    }

    @Test
    void aNulCharacterInAStringIsRefused() throws Exception {
        Fault fault = check("k", null, json("{\"note\":\"a\\u0000b\"}"), false, 0);

        assertThat(fault.code()).isEqualTo("DATA_STORE_VALUE_INVALID");
        assertThat(fault.field()).isEqualTo("value");
        assertThat(fault.message().code()).isEqualTo("workflow.dataStore.valueInvalid");
    }

    @Test
    void aNulCharacterInAFieldNameIsRefused() throws Exception {
        assertThat(check("k", null, json("{\"a\\u0000b\":1}"), false, 0).code())
                .isEqualTo("DATA_STORE_VALUE_INVALID");
    }

    @Test
    void aNulCharacterNestedInAnArrayIsRefused() throws Exception {
        assertThat(check("k", null, json("[{\"x\":[\"\\u0000\"]}]"), false, 0).code())
                .isEqualTo("DATA_STORE_VALUE_INVALID");
    }

    @Test
    void theEscapeTextOfANulWithoutTheCharacterPasses() throws Exception {
        assertThat(check("k", null, json("{\"note\":\"a\\\\u0000b\"}"), false, 0)).isNull();
    }

    @Test
    void aMissingValueIsRefusedAsInvalid() {
        assertThat(check("k", null, null, false, 0).code()).isEqualTo("DATA_STORE_VALUE_INVALID");
    }

    @Test
    void aJsonNullValueIsAccepted() {
        assertThat(check("k", null, JsonNodeFactory.instance.nullNode(), false, 0)).isNull();
    }

    @Test
    void aNumberUpToNineHundredDigitsWhenWrittenOutPasses() throws Exception {
        JsonNode value = DataStoreJson.mapper().readTree("{\"a\":[1e400,1e899,1e-900,3.141592653589793238462643]}");

        assertThat(check("k", null, value, false, 0)).isNull();
    }

    @Test
    void aNumberLongerWhenWrittenOutIsRefusedWhereverItSits() throws Exception {
        for (String text : new String[] {"1e900", "1e-901", "{\"a\":[{\"b\":1e5000}]}", "1e999999999", "-1.5e-999999999"}) {
            JsonNode value = DataStoreJson.mapper().readTree(text);

            Fault fault = check("k", null, value, false, 0);

            assertThat(fault).as("value %s", text).isNotNull();
            assertThat(fault.code()).isEqualTo("DATA_STORE_NUMBER_TOO_LONG");
            assertThat(fault.field()).isEqualTo("value");
            assertThat(fault.message().code()).isEqualTo("workflow.dataStore.numberTooLong");
        }
    }

    @Test
    void sizeKeepsADecimalsDigits() throws Exception {
        assertThat(DataStoreRules.sizeOf(DataStoreJson.mapper().readTree("3.141592653589793238462643")))
                .isEqualTo(26);
        assertThat(DataStoreRules.sizeOf(DataStoreJson.mapper().readTree("1.00"))).isEqualTo(4);
    }

    @Test
    void aValueAtTheSizeLimitPassesAndOneByteOverIsTooLarge() {
        assertThat(check("k", null, stringOfCompactSize(524_288), false, 0)).isNull();

        Fault fault = check("k", null, stringOfCompactSize(524_289), false, 0);
        assertThat(fault.code()).isEqualTo("DATA_STORE_VALUE_TOO_LARGE");
        assertThat(fault.field()).isEqualTo("value");
        assertThat(fault.message().code()).isEqualTo("workflow.dataStore.valueTooLarge");
    }

    @Test
    void aNewKeyAtNinetyNineKeysPassesAndAtAHundredIsRefused() {
        assertThat(check("k", null, OK_VALUE, false, 99)).isNull();

        Fault fault = check("k", null, OK_VALUE, false, 100);
        assertThat(fault.code()).isEqualTo("DATA_STORE_QUOTA_EXCEEDED");
        assertThat(fault.field()).isEqualTo("key");
        assertThat(fault.message().code()).isEqualTo("workflow.dataStore.quotaExceeded");
    }

    @Test
    void anExistingKeyAtAHundredKeysPasses() {
        assertThat(check("k", null, OK_VALUE, true, 100)).isNull();
    }

    @Test
    void theFirstFaultInTheOrderKeyTypeValueSizeQuotaWins() {
        ObjectNode nul = MAPPER.createObjectNode().put("a", "\u0000");
        JsonNode tooLarge = stringOfCompactSize(524_289);

        assertThat(check("a b", "Bad", nul, false, 100).code()).isEqualTo("DATA_STORE_KEY_INVALID");
        assertThat(check("k", "Bad", nul, false, 100).code()).isEqualTo("DATA_STORE_TYPE_INVALID");
        assertThat(check("k", "ok", nul, false, 100).code()).isEqualTo("DATA_STORE_VALUE_INVALID");
        assertThat(check("k", "ok", tooLarge, false, 100).code()).isEqualTo("DATA_STORE_VALUE_TOO_LARGE");
        assertThat(check("k", "ok", OK_VALUE, false, 100).code()).isEqualTo("DATA_STORE_QUOTA_EXCEEDED");
    }
}
