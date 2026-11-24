package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.workflow.application.IterateItems.Classified;
import com.flowcortex.workflow.application.IterateItems.Items;
import com.flowcortex.workflow.application.IterateItems.NotAList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class IterateItemsTest {

    private static Items items(String input, int maxItems) {
        Classified classified = IterateItems.classify(input, maxItems);
        assertThat(classified).isInstanceOf(Items.class);
        return (Items) classified;
    }

    @Test
    void jsonArray() {
        Items items = items("[\"a\",{\"t\":1},2,true,null,[1]]", 50);

        assertThat(items.inputs()).containsExactly("a", "{\"t\":1}", "2", "true", "null", "[1]");
        assertThat(items.dropped()).isZero();
    }

    @Test
    void fencedJsonArrayText() {
        Items items = items("```json\n[\"x\",\"y\"]\n```", 50);

        assertThat(items.inputs()).containsExactly("x", "y");
        assertThat(items("  ```json\n[1]\n```  ", 50).inputs()).containsExactly("1");
    }

    @Test
    void objectWithOneArrayField() {
        Items items = items("{\"posts\":[{\"t\":1},{\"t\":2}],\"count\":2}", 50);

        assertThat(items.inputs()).containsExactly("{\"t\":1}", "{\"t\":2}");
    }

    @Test
    void objectWithTwoArrayFieldsIsNotAList() {
        assertThat(IterateItems.classify("{\"a\":[1],\"b\":[2]}", 50)).isInstanceOf(NotAList.class);
        assertThat(IterateItems.classify("{\"a\":1}", 50)).isInstanceOf(NotAList.class);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  ", "hello", "42", "true", "null", "\"[1]\"", "[1,2", "[1] trailing",
            "```json\n{\"a\":[1]}\n```", "```json\n[1]"})
    void scalarAndPlainTextAreNotAList(String input) {
        assertThat(IterateItems.classify(input, 50)).isInstanceOf(NotAList.class);
    }

    @Test
    void emptyArrayIsAnEmptyList() {
        Items items = items("[]", 50);

        assertThat(items.inputs()).isEmpty();
        assertThat(items.dropped()).isZero();
    }

    @Test
    void capKeepsFirstMaxItemsAndCountsDropped() {
        Items items = items("[1,2,3,4,5]", 3);

        assertThat(items.inputs()).containsExactly("1", "2", "3");
        assertThat(items.dropped()).isEqualTo(2);
    }

    @Test
    void textItemUnquotedAndObjectItemCompactJson() {
        Items items = items("[\"line \\\"one\\\"\",{ \"a\" : 1 , \"b\" : [ 1 , 2 ] }]", 50);

        assertThat(items.inputs()).containsExactly("line \"one\"", "{\"a\":1,\"b\":[1,2]}");
    }

    @Test
    void renderWholeTokenKeepsJsonType() {
        String rendered = IterateItems.render("{\"item\":\"{item}\",\"n\":\"{index}\",\"t\":\"{item.title}\","
                + "\"list\":[\"{index}\",\"{item.tags}\"]}", "{\"title\":\"T\",\"tags\":[\"x\"]}", false, 4);

        assertThat(rendered).isEqualTo("{\"item\":{\"title\":\"T\",\"tags\":[\"x\"]},\"n\":4,\"t\":\"T\","
                + "\"list\":[4,[\"x\"]]}");
    }

    @Test
    void renderInsideTextStringifies() {
        String rendered = IterateItems.render("{\"a\":\"o={item}|n={item.n}|z={item.z}|b={item.b}|i={index}\"}",
                "{\"k\":1,\"n\":2.5,\"z\":null,\"b\":false}", false, 7);

        assertThat(rendered).isEqualTo("{\"a\":\"o={\\\"k\\\":1,\\\"n\\\":2.5,\\\"z\\\":null,\\\"b\\\":false}"
                + "|n=2.5|z=|b=false|i=7\"}");
    }

    @Test
    void renderTextItemIsTheText() {
        assertThat(IterateItems.render("{\"a\":\"{item}\",\"b\":\"say {item}!\"}", "hello world", true, 0))
                .isEqualTo("{\"a\":\"hello world\",\"b\":\"say hello world!\"}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"42", "true", "null", "[1]", "{\"a\":1}"})
    void renderStringElementThatReadsAsJsonStaysAString(String element) {
        String quoted = "\"" + element.replace("\"", "\\\"") + "\"";

        assertThat(IterateItems.isTextItem("[" + quoted + "]", 0, element)).isTrue();
        assertThat(IterateItems.render("{\"a\":\"{item}\"}", element, true, 0)).isEqualTo("{\"a\":" + quoted + "}");
    }

    @Test
    void renderNonStringElementKeepsItsJsonType() {
        assertThat(IterateItems.isTextItem("[42,\"42\"]", 0, "42")).isFalse();
        assertThat(IterateItems.isTextItem("[42,\"42\"]", 1, "42")).isTrue();
        assertThat(IterateItems.render("{\"a\":\"{item}\"}", "42", false, 0)).isEqualTo("{\"a\":42}");
    }

    @Test
    void isTextItemIsFalseWhenTheListCannotBeReadOrDoesNotMatch() {
        assertThat(IterateItems.isTextItem(null, 0, "a")).isFalse();
        assertThat(IterateItems.isTextItem("not a list", 0, "a")).isFalse();
        assertThat(IterateItems.isTextItem("[\"a\"]", 1, "a")).isFalse();
        assertThat(IterateItems.isTextItem("[\"a\"]", -1, "a")).isFalse();
        assertThat(IterateItems.isTextItem("[\"a\"]", 0, "b")).isFalse();
    }

    @Test
    void renderNestedPath() {
        assertThat(IterateItems.render("{\"a\":\"{item.a.b.c}\"}", "{\"a\":{\"b\":{\"c\":[1]}}}", false, 0))
                .isEqualTo("{\"a\":[1]}");
    }

    @Test
    void renderMissingPathThrowsItemFieldMissing() {
        assertThatThrownBy(() -> IterateItems.render("{\"text\":\"{item.caption}\"}", "{\"title\":\"A\"}", false, 0))
                .isInstanceOf(StepFailedException.class)
                .extracting(e -> ((StepFailedException) e).code())
                .isEqualTo("iterate_item_field_missing");
        assertThatThrownBy(() -> IterateItems.render("{\"text\":\"for {item.a.b}\"}", "{\"a\":1}", false, 0))
                .isInstanceOf(StepFailedException.class);
        assertThatThrownBy(() -> IterateItems.render("{\"text\":\"{item.a}\"}", "plain text", true, 0))
                .isInstanceOf(StepFailedException.class);
    }

    @Test
    void renderLeavesOtherBracesAlone() {
        String config = "{\"a\":\"{other}\",\"b\":\"{item }\",\"c\":\"{{item}}x\",\"d\":5,\"e\":null,\"{item}\":1}";

        assertThat(IterateItems.render(config, "x", true, 0))
                .isEqualTo("{\"a\":\"{other}\",\"b\":\"{item }\",\"c\":\"{x}x\",\"d\":5,\"e\":null,\"{item}\":1}");
    }

    @Test
    void renderTypedAndInterpolatedScenario() {
        String config = "{\"lang\":\"vi\",\"item\":\"{item}\",\"label\":\"Cảnh {index}: {item.title}\",\"n\":\"{index}\"}";

        assertThat(IterateItems.render(config, "{\"title\":\"Mở đầu\"}", false, 1)).isEqualTo(
                "{\"lang\":\"vi\",\"item\":{\"title\":\"Mở đầu\"},\"label\":\"Cảnh 1: Mở đầu\",\"n\":1}");
    }

    @Test
    void renderOfAnEmptyConfigIsAnEmptyObject() {
        assertThat(IterateItems.render("{}", "x", true, 0)).isEqualTo("{}");
    }

    @Test
    void renderOfAnUnreadableConfigFailsTheChild() {
        assertThatThrownBy(() -> IterateItems.render("{broken", "x", true, 0))
                .isInstanceOf(StepFailedException.class)
                .extracting(e -> ((StepFailedException) e).code())
                .isEqualTo("iterate_config_invalid");
    }
}
