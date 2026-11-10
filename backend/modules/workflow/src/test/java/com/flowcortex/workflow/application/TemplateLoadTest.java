package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class TemplateLoadTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String PROMO = """
            {"data":{"brief":"Spring sale","defaults":{"tone":"friendly","cta":"Buy now"}},
             "inputs":{"fields":{"productName":{"required":true},"tone":{},"cta":{}}}}""";

    private final List<String> lookedUp = new ArrayList<>();

    private Function<String, Optional<String>> store(Map<String, String> entries) {
        return key -> {
            lookedUp.add(key);
            return Optional.ofNullable(entries.get(key));
        };
    }

    private void assertFailed(TemplateLoad.Result result, String code) {
        assertThat(result).isInstanceOfSatisfying(TemplateLoad.Failed.class, failed -> {
            assertThat(failed.code()).isEqualTo(code);
            assertThat(failed.message()).isNotBlank();
        });
    }

    private JsonNode loaded(TemplateLoad.Result result) throws Exception {
        assertThat(result).isInstanceOf(TemplateLoad.Loaded.class);
        return JSON.readTree(((TemplateLoad.Loaded) result).json());
    }

    @Test
    void inputThatIsNotAJsonObjectWithATemplateKeyIsInvalidAndNeverLooksUp() {
        for (String input : new String[] {"hello", "[1]", "{}", "{\"template\":\"\"}", "{\"template\":42}", null}) {
            assertFailed(TemplateLoad.load(input, store(Map.of("promo", PROMO))), "load_template_invalid_input");
        }
        assertThat(lookedUp).isEmpty();
    }

    @Test
    void anAbsentKeyIsNotFound() {
        assertFailed(TemplateLoad.load("{\"template\":\"nope\"}", store(Map.of())), "load_template_not_found");
        assertThat(lookedUp).containsExactly("nope");
    }

    @Test
    void anEntryThatIsNotOfTheTemplateShapeIsInvalidTemplate() {
        for (String value : new String[] {
            "{\"data\":\"text\"}",
            "{}",
            "{\"data\":{},\"inputs\":{\"fields\":[]}}",
            "{\"data\":{},\"inputs\":{\"fields\":{\"a\":1}}}",
            "{\"data\":{},\"inputs\":{\"fields\":{\"a\":{\"required\":\"yes\"}}}}",
            "not json"
        }) {
            assertFailed(TemplateLoad.load("{\"template\":\"bad\"}", store(Map.of("bad", value))),
                    "load_template_invalid_template");
        }
    }

    @Test
    void inputWinsDefaultFillsAndUndeclaredIsDropped() throws Exception {
        String input = "{\"template\":\"promo\",\"productName\":\"Nodi Mug\",\"tone\":\"  \",\"extra\":\"x\"}";

        JsonNode out = loaded(TemplateLoad.load(input, store(Map.of("promo", PROMO))));

        assertThat(out).isEqualTo(JSON.readTree(
                "{\"brief\":\"Spring sale\",\"productName\":\"Nodi Mug\",\"tone\":\"friendly\",\"cta\":\"Buy now\"}"));
        assertThat(out.fieldNames()).toIterable().containsExactly("brief", "productName", "tone", "cta");
    }

    @Test
    void anOptionalFieldWithNoDefaultIsAnEmptyString() throws Exception {
        String template = "{\"data\":{},\"inputs\":{\"fields\":{\"note\":{}}}}";

        JsonNode out = loaded(TemplateLoad.load("{\"template\":\"t\"}", store(Map.of("t", template))));

        assertThat(out.get("note").isTextual()).isTrue();
        assertThat(out.get("note").asText()).isEmpty();
    }

    @Test
    void aNonStringInputValueIsKeptEvenWhenZero() throws Exception {
        String template = "{\"data\":{},\"inputs\":{\"fields\":{\"count\":{}}}}";

        JsonNode out = loaded(TemplateLoad.load("{\"template\":\"t\",\"count\":0}", store(Map.of("t", template))));

        assertThat(out.get("count").isInt()).isTrue();
        assertThat(out.get("count").asInt()).isZero();
    }

    @Test
    void aNullInputValueCountsAsNotFilled() throws Exception {
        String template = "{\"data\":{\"defaults\":{\"n\":\"d\"}},\"inputs\":{\"fields\":{\"n\":{}}}}";

        JsonNode out = loaded(TemplateLoad.load("{\"template\":\"t\",\"n\":null}", store(Map.of("t", template))));

        assertThat(out.get("n").asText()).isEqualTo("d");
    }

    @Test
    void requiredFieldsBlankOrAbsentFailNamingEveryOneInDeclarationOrder() {
        String template = "{\"data\":{\"defaults\":{\"productImage\":\"d.png\"}},"
                + "\"inputs\":{\"fields\":{\"productName\":{\"required\":true},\"productImage\":{\"required\":true}}}}";

        TemplateLoad.Result result =
                TemplateLoad.load("{\"template\":\"t\",\"productName\":\" \"}", store(Map.of("t", template)));

        assertFailed(result, "load_template_missing_input");
        // The frontend parses this exact shape to name the fields in the member's language.
        assertThat(((TemplateLoad.Failed) result).message())
                .isEqualTo("Required input missing: productName, productImage.");
    }

    @Test
    void dataWithoutDefaultsAndNoInputsComesBackUnchanged() throws Exception {
        String template = "{\"data\":{\"brief\":\"Spring sale\",\"n\":1}}";

        JsonNode out = loaded(TemplateLoad.load("{\"template\":\"t\",\"x\":1}", store(Map.of("t", template))));

        assertThat(out).isEqualTo(JSON.readTree("{\"brief\":\"Spring sale\",\"n\":1}"));
    }

    @Test
    void decimalsInTheTemplateAndTheInputReachTheOutputWithTheirDigits() throws Exception {
        String template = "{\"data\":{\"pi\":3.141592653589793238462643,\"price\":1.00,\"huge\":1e400},"
                + "\"inputs\":{\"fields\":{\"amount\":{}}}}";

        TemplateLoad.Result result = TemplateLoad.load(
                "{\"template\":\"t\",\"amount\":0.1000000000000000055511151231257827}", store(Map.of("t", template)));

        assertThat(result).isInstanceOf(TemplateLoad.Loaded.class);
        JsonNode out = DataStoreJson.mapper().readTree(((TemplateLoad.Loaded) result).json());
        assertThat(out.get("pi").decimalValue()).isEqualTo(new BigDecimal("3.141592653589793238462643"));
        assertThat(out.get("price").decimalValue()).isEqualTo(new BigDecimal("1.00"));
        assertThat(out.get("huge").decimalValue()).isEqualTo(new BigDecimal("1e400"));
        assertThat(out.get("amount").decimalValue())
                .isEqualTo(new BigDecimal("0.1000000000000000055511151231257827"));
    }

    @Test
    void messagesNeverEchoTheTemplateContent() {
        String secret = "{\"data\":{\"secret\":\"top-secret-value\"},\"inputs\":{\"fields\":{\"a\":1}}}";

        TemplateLoad.Result result = TemplateLoad.load("{\"template\":\"t\"}", store(Map.of("t", secret)));

        assertThat(((TemplateLoad.Failed) result).message()).doesNotContain("top-secret-value");
    }

    @Test
    void shapeFaultIsNullForATemplateAndNamedOtherwise() throws Exception {
        assertThat(TemplateLoad.shapeFault(JSON.readTree(PROMO))).isNull();
        assertThat(TemplateLoad.shapeFault(JSON.readTree("{\"data\":\"text\"}"))).isNotBlank();
        assertThat(TemplateLoad.shapeFault(JSON.readTree("[]"))).isNotBlank();
    }
}
