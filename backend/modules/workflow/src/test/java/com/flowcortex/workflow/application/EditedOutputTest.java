package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.workflow.application.EditedOutput.Applied;
import com.flowcortex.workflow.application.EditedOutput.Field;
import com.flowcortex.workflow.application.EditedOutput.FieldEdit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Reading, applying and checking the editable text fields of a step output, as pure functions. */
class EditedOutputTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    static final String ECHO = "Hello world";
    static final String SCRIPT = "{\"profile\":\"product_ad_v1\",\"language\":\"vi\",\"script\":\"Áo thun mát\"}";
    static final String TRANSLATE = "{\"segments\":["
            + "{\"index\":0,\"startMs\":0,\"endMs\":2000,\"zh\":\"你好\",\"vi\":\"Xin chào\"},"
            + "{\"index\":1,\"startMs\":2000,\"endMs\":4000,\"zh\":\"谢谢\",\"vi\":\"Cám ơn\"}]}";

    private static FieldEdit edit(String pointer, String text) {
        return new FieldEdit(pointer, text);
    }

    private static Applied apply(String type, String original, Map<String, String> current, FieldEdit... edits) {
        return EditedOutput.apply(type, original, current, List.of(edits));
    }

    private static JsonNode tree(String json) throws Exception {
        return JSON.readTree(json);
    }

    private static void refused(String type, String original, String field, FieldEdit... edits) {
        assertThatThrownBy(() -> EditedOutput.apply(type, original, Map.of(), List.of(edits)))
                .isInstanceOfSatisfying(FieldRefusal.class, e -> assertThat(e.field()).isEqualTo(field));
    }

    private static String manySegments(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> "{\"index\":" + i + ",\"startMs\":" + i * 10 + ",\"endMs\":" + (i * 10 + 5)
                        + ",\"zh\":\"z\",\"vi\":\"v\"}")
                .collect(Collectors.joining(",", "{\"segments\":[", "]}"));
    }

    @Test
    void echoHasOneWholeTextField() {
        assertThat(EditedOutput.fields("echo", ECHO))
                .containsExactly(new Field("", "output", null, null, "Hello world"));
    }

    @Test
    void scriptHasOneScriptField() {
        assertThat(EditedOutput.fields("generate_script", SCRIPT))
                .containsExactly(new Field("/script", "script", null, null, "Áo thun mát"));
    }

    @Test
    void translateHasALineFieldPerSegmentWithItsSource() {
        assertThat(EditedOutput.fields("translate", TRANSLATE)).containsExactly(
                new Field("/segments/0/vi", "line", 0, "你好", "Xin chào"),
                new Field("/segments/1/vi", "line", 1, "谢谢", "Cám ơn"));
    }

    @Test
    void anOutputWithoutItsShapeHasNoField() {
        assertThat(EditedOutput.fields("generate_script", "{\"profile\":\"product_ad_v1\"}")).isEmpty();
        assertThat(EditedOutput.fields("translate", "{\"segments\":\"x\"}")).isEmpty();
        assertThat(EditedOutput.fields("translate", "not json")).isEmpty();
        assertThat(EditedOutput.fields("echo", null)).isEmpty();
        assertThat(EditedOutput.fields("tts", "{\"voiced\":3}")).isEmpty();
    }

    @Test
    void anEchoOfJsonStructureHasNoField() {
        assertThat(EditedOutput.fields("echo", "{\"a\":1}")).isEmpty();
        assertThat(EditedOutput.fields("echo", "[1]")).isEmpty();
        assertThat(EditedOutput.fields("echo", " {\"a\":1} ")).isEmpty();
        assertThat(EditedOutput.fields("echo", "{not json")).hasSize(1);
        assertThat(EditedOutput.fields("echo", "Hello {x}")).hasSize(1);
        assertThat(EditedOutput.fields("echo", "42")).hasSize(1);
    }

    @Test
    void firstEditOfAPlainTextResult() {
        Applied applied = apply("echo", ECHO, Map.of(), edit("", "Hello Hanoi"));

        assertThat(applied.output()).isEqualTo("Hello Hanoi");
        assertThat(applied.values()).containsExactly(Map.entry("", "Hello Hanoi"));
    }

    @Test
    void scriptOfGenerateScript() throws Exception {
        Applied applied = apply("generate_script", SCRIPT, Map.of(), edit("/script", "Áo thun mát lạnh"));

        assertThat(tree(applied.output())).isEqualTo(
                tree("{\"profile\":\"product_ad_v1\",\"language\":\"vi\",\"script\":\"Áo thun mát lạnh\"}"));
    }

    @Test
    void oneLineOfTranslate() throws Exception {
        Applied applied = apply("translate", TRANSLATE, Map.of(), edit("/segments/1/vi", "Cảm ơn bạn"));

        assertThat(tree(applied.output())).isEqualTo(tree(TRANSLATE.replace("Cám ơn", "Cảm ơn bạn")));
        assertThat(applied.values()).containsExactly(Map.entry("/segments/0/vi", "Xin chào"),
                Map.entry("/segments/1/vi", "Cảm ơn bạn"));
    }

    @Test
    void fieldsNotSentKeepTheirCurrentValue() throws Exception {
        Map<String, String> current = new LinkedHashMap<>();
        current.put("/segments/0/vi", "Xin chào");
        current.put("/segments/1/vi", "Cảm ơn bạn");

        Applied applied = apply("translate", TRANSLATE, current, edit("/segments/0/vi", "Chào bạn"));

        assertThat(tree(applied.output())).isEqualTo(
                tree(TRANSLATE.replace("Xin chào", "Chào bạn").replace("Cám ơn", "Cảm ơn bạn")));
    }

    @Test
    void anEmptyTranslateLineIsAccepted() throws Exception {
        Applied applied = apply("translate", TRANSLATE, Map.of(), edit("/segments/0/vi", ""));

        assertThat(tree(applied.output()).at("/segments/0/vi").textValue()).isEmpty();
    }

    @Test
    void aTextEqualToTheCurrentValueIsAccepted() {
        assertThat(apply("echo", ECHO, Map.of(), edit("", ECHO)).output()).isEqualTo(ECHO);
    }

    @Test
    void nulIsRemoved() {
        assertThat(apply("echo", ECHO, Map.of(), edit("", "A\u0000B")).output()).isEqualTo("AB");
    }

    @Test
    void structureChangesAreRefusedNamingThePointer() {
        refused("generate_script", SCRIPT, "fields[0].pointer", edit("/profile", "x"));
        refused("translate", TRANSLATE, "fields[0].pointer", edit("/segments/0/zh", "x"));
        refused("translate", TRANSLATE, "fields[0].pointer", edit("/segments/0/startMs", "1"));
        refused("translate", TRANSLATE, "fields[0].pointer", edit("/segments/7/vi", "x"));
        refused("translate", TRANSLATE, "fields[0].pointer", edit("/segments", "x"));
        refused("echo", ECHO, "fields[0].pointer", edit("/script", "x"));
    }

    @Test
    void theIndexOfTheOffendingFieldIsNamed() {
        refused("translate", TRANSLATE, "fields[1].pointer", edit("/segments/0/vi", "ok"), edit("/segments/9/vi", "x"));
    }

    @Test
    void textsBreakingTheirFieldRuleAreRefusedNamingTheText() {
        refused("echo", ECHO, "fields[0].text", edit("", "   "));
        refused("generate_script", SCRIPT, "fields[0].text", edit("/script", " "));
        refused("generate_script", SCRIPT, "fields[0].text", edit("/script", "s".repeat(8_001)));
        refused("translate", TRANSLATE, "fields[0].text", edit("/segments/0/vi", "l".repeat(1_001)));
        refused("echo", ECHO, "fields[0].text", edit("", "e".repeat(100_001)));
    }

    @Test
    void limitsCountUtf16Characters() {
        String supplementary = "😀".repeat(501);

        refused("translate", TRANSLATE, "fields[0].text", edit("/segments/0/vi", supplementary));
    }

    @Test
    void aWholeTextThatWouldBecomeJsonStructureIsRefused() {
        refused("echo", ECHO, "fields[0].text", edit("", "{\"a\":1}"));
        refused("echo", ECHO, "fields[0].text", edit("", "[1,2]"));
        assertThat(apply("echo", ECHO, Map.of(), edit("", "{not json")).output()).isEqualTo("{not json");
        assertThat(apply("echo", ECHO, Map.of(), edit("", "Hello {x}")).output()).isEqualTo("Hello {x}");
    }

    @Test
    void aBadFieldListIsRefusedNamingFields() {
        refused("echo", ECHO, "fields");
        refused("translate", TRANSLATE, "fields", edit("/segments/0/vi", "a"), edit("/segments/0/vi", "b"));
        List<FieldEdit> tooMany = IntStream.range(0, 2_001).mapToObj(i -> edit("/segments/" + i + "/vi", "x")).toList();
        assertThatThrownBy(() -> EditedOutput.apply("translate", TRANSLATE, Map.of(), tooMany))
                .isInstanceOfSatisfying(FieldRefusal.class, e -> assertThat(e.field()).isEqualTo("fields"));
    }

    @Test
    void textsSummingOverTheLimitAreRefusedNamingFields() {
        List<FieldEdit> edits = IntStream.range(0, 101)
                .mapToObj(i -> edit("/segments/" + i + "/vi", "t".repeat(1_000))).toList();

        assertThatThrownBy(() -> EditedOutput.apply("translate", manySegments(101), Map.of(), edits))
                .isInstanceOfSatisfying(FieldRefusal.class, e -> assertThat(e.field()).isEqualTo("fields"));
    }

    @Test
    void aStoredVersionAboveTheRequestLimitsIsRestored() throws Exception {
        String original = manySegments(StepEditRules.MAX_FIELDS + 1);
        Map<String, String> stored = EditedOutput.values("translate", original);
        stored.put("/segments/0/vi", "t".repeat(1_000));
        stored.put("/segments/1/vi", "");

        Applied restored = EditedOutput.restore("translate", original, stored);

        assertThat(restored.values()).isEqualTo(stored);
        assertThat(tree(restored.output()).at("/segments/0/vi").textValue()).hasSize(1_000);
        assertThat(tree(restored.output()).at("/segments/1/vi").textValue()).isEmpty();
        assertThat(tree(restored.output()).at("/segments/2000/vi").textValue()).isEqualTo("v");
    }

    @Test
    void aStoredVersionKeepsTheOriginalTextOfAFieldItLacks() throws Exception {
        Applied restored = EditedOutput.restore("translate", TRANSLATE, Map.of("/segments/1/vi", "Tạm biệt"));

        assertThat(restored.values()).containsExactly(Map.entry("/segments/0/vi", "Xin chào"),
                Map.entry("/segments/1/vi", "Tạm biệt"));
        assertThat(tree(restored.output()).at("/segments/0/vi").textValue()).isEqualTo("Xin chào");
    }

    @Test
    void aStoredVersionWithAPointerThatIsNotAFieldIsRefused() {
        assertThatThrownBy(() -> EditedOutput.restore("translate", TRANSLATE, Map.of("/segments/0/zh", "x")))
                .isInstanceOf(FieldRefusal.class);
        assertThatThrownBy(() -> EditedOutput.restore("translate", TRANSLATE, Map.of("/segments/9/vi", "x")))
                .isInstanceOf(FieldRefusal.class);
    }

    @Test
    void aStoredVersionWithoutATextIsRefused() {
        Map<String, String> nulled = new LinkedHashMap<>();
        nulled.put("/segments/0/vi", null);

        assertThatThrownBy(() -> EditedOutput.restore("translate", TRANSLATE, nulled))
                .isInstanceOf(FieldRefusal.class);
    }

    @Test
    void aBrokenApplyIsCaughtByThePostCondition() {
        String broken = SCRIPT.replace("product_ad_v1", "other");

        assertThatThrownBy(() -> EditedOutput.assertStructureKept("generate_script", SCRIPT, broken))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> EditedOutput.assertStructureKept("translate", TRANSLATE,
                TRANSLATE.replace("\"startMs\":2000", "\"startMs\":2001"))).isInstanceOf(IllegalStateException.class);
        EditedOutput.assertStructureKept("generate_script", SCRIPT, SCRIPT.replace("Áo thun mát", "Khác"));
    }

    @Test
    void valuesOfAnEditedTranslateOutput() {
        Map<String, String> values = EditedOutput.values("translate", TRANSLATE.replace("Cám ơn", "Cảm ơn bạn"));

        assertThat(values).containsExactly(Map.entry("/segments/0/vi", "Xin chào"),
                Map.entry("/segments/1/vi", "Cảm ơn bạn"));
    }

    @Test
    void onlyTheTextFieldsCountForThePercentage() {
        Map<String, String> original = EditedOutput.values("translate", TRANSLATE);
        Map<String, String> edited = EditedOutput.values("translate", TRANSLATE.replace("Cám ơn", "Cảm ơn"));

        assertThat(EditedOutput.diffText(original)).isEqualTo("Xin chào\nCám ơn");
        assertThat(EditDistance.percent(EditedOutput.diffText(original), EditedOutput.diffText(edited)))
                .isEqualByComparingTo("6.67");
    }
}
