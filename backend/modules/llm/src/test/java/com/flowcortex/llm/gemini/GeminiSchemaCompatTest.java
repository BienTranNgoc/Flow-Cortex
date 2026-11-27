package com.flowcortex.llm.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class GeminiSchemaCompatTest {

    /** The text of the dub translation schema, as the workflow module sends it. */
    private static final String DUB_LINES_SCHEMA = "{\"type\":\"ARRAY\",\"items\":{\"type\":\"OBJECT\",\"properties\":"
            + "{\"index\":{\"type\":\"INTEGER\"},\"vi\":{\"type\":\"STRING\"}},\"required\":[\"index\",\"vi\"]}}";

    /** The text of the assistant's answer schema, as the support module sends it. */
    private static final String ASSISTANT_RESPONSE_SCHEMA = """
            {"type":"OBJECT","properties":{"answered":{"type":"BOOLEAN"},"answer":{"type":"STRING"},\
            "citedSlugs":{"type":"ARRAY","items":{"type":"STRING"}}},\
            "required":["answered","answer","citedSlugs"]}""";

    private static List<String> violations(String schema) {
        return GeminiSchemaCompat.violations(GeminiAttempts.JSON.readTree(schema));
    }

    @Test
    void anIntegerEnumIsNamedByItsFieldPath() {
        List<String> violations = violations("""
                {"type":"OBJECT","properties":{"scenes":{"type":"ARRAY","items":{"type":"OBJECT","properties":{
                "duration_sec":{"type":"INTEGER","enum":[5,10]}}}}}}""");

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).startsWith("scenes.items.duration_sec").contains("enum");
    }

    @Test
    void anEnumWithoutATypeButANumberIsAViolation() {
        List<String> violations = violations("""
                {"type":"OBJECT","properties":{"level":{"enum":["low",2]}}}""");

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).startsWith("level").contains("enum");
    }

    @Test
    void aStringTypedEnumOfStringsPasses() {
        assertThat(violations("""
                {"type":"OBJECT","properties":{"tone":{"type":"STRING","enum":["warm","formal"]}}}""")).isEmpty();
    }

    @Test
    void propertiesUnderAnArrayIsNamed() {
        List<String> violations = violations("""
                {"type":"OBJECT","properties":{"ideas":{"type":"ARRAY","properties":{"title":{"type":"STRING"}}}}}""");

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).startsWith("ideas").contains("properties");
    }

    @Test
    void itemsUnderAnObjectIsNamed() {
        List<String> violations = violations("""
                {"type":"OBJECT","properties":{"box":{"type":"OBJECT","items":{"type":"STRING"}}}}""");

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).startsWith("box").contains("items");
    }

    @Test
    void aNodeWithoutATypeHoldingPropertiesIsAViolation() {
        List<String> violations = violations("""
                {"type":"OBJECT","properties":{"loose":{"properties":{"a":{"type":"STRING"}}}}}""");

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).startsWith("loose").contains("properties");
    }

    @Test
    void typeNamesCompareWithoutCase() {
        assertThat(violations("""
                {"type":"OBJECT","properties":{"a":{"type":"array","items":{"type":"object","properties":\
                {"b":{"type":"String"}}}}}}""")).isEmpty();
        assertThat(violations("""
                {"type":"object","properties":{"a":{"type":"ARRAY","items":{"type":"STRING"}}}}""")).isEmpty();
    }

    @Test
    void tupleItemsAndCompositeBranchesAreWalked() {
        List<String> tuple = violations("""
                {"type":"ARRAY","items":[{"type":"STRING"},{"type":"INTEGER","enum":[1]}]}""");
        List<String> branches = violations("""
                {"anyOf":[{"type":"STRING"},{"type":"INTEGER","enum":[1]}]}""");
        List<String> oneOf = violations("""
                {"oneOf":[{"type":"ARRAY","properties":{"x":{"type":"STRING"}}}]}""");

        assertThat(tuple).hasSize(1);
        assertThat(tuple.get(0)).startsWith("items.1");
        assertThat(branches).hasSize(1);
        assertThat(branches.get(0)).startsWith("anyOf.1");
        assertThat(oneOf).hasSize(1);
        assertThat(oneOf.get(0)).startsWith("oneOf.0");
    }

    @Test
    void everyViolationIsListed() {
        List<String> violations = violations("""
                {"type":"OBJECT","properties":{"a":{"type":"INTEGER","enum":[1]},\
                "b":{"type":"ARRAY","properties":{"c":{"type":"STRING"}}}}}""");

        assertThat(violations).hasSize(2);
    }

    @Test
    void aSchemaThatIsNotAnObjectHasNothingToCheck() {
        assertThat(GeminiSchemaCompat.violations(null)).isEmpty();
        assertThat(violations("true")).isEmpty();
    }

    @Test
    void theShippedDubAndAssistantSchemasPass() {
        assertThat(violations(DUB_LINES_SCHEMA)).isEmpty();
        assertThat(violations(ASSISTANT_RESPONSE_SCHEMA)).isEmpty();
    }
}
