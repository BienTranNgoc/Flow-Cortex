package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.workflow.application.QaQuestions.QaQuestion;
import com.flowcortex.workflow.application.QaQuestions.Type;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class QaQuestionsTest {

    private static String single(String id) {
        return "{\"id\":\"" + id + "\",\"type\":\"single\",\"question\":\"Who?\",\"options\":[\"a\",\"b\"]}";
    }

    private static String array(String... elements) {
        return "[" + String.join(",", elements) + "]";
    }

    private static String question(String type, String options) {
        return "{\"id\":\"q1\",\"type\":\"" + type + "\",\"question\":\"Who?\""
                + (options == null ? "" : ",\"options\":" + options) + "}";
    }

    @Test
    void oneToFiveQuestionsAccepted() {
        String text = "{\"id\":\"notes\",\"type\":\"text\",\"question\":\"Anything else?\"}";
        String multi = "{\"id\":\"m_1\",\"type\":\"multi\",\"question\":\"Which?\",\"options\":[\"x\",\"y\",\"z\"]}";

        Optional<List<QaQuestion>> parsed = QaQuestions.parse(array(single("q1"), multi, text));

        assertThat(parsed).isPresent();
        assertThat(parsed.get()).containsExactly(
                new QaQuestion("q1", Type.SINGLE, "Who?", List.of("a", "b")),
                new QaQuestion("m_1", Type.MULTI, "Which?", List.of("x", "y", "z")),
                new QaQuestion("notes", Type.TEXT, "Anything else?", List.of()));
        assertThat(QaQuestions.parse(array(single("q1")))).isPresent();
        String five = IntStream.rangeClosed(1, 5).mapToObj(i -> single("q" + i)).collect(Collectors.joining(","));
        assertThat(QaQuestions.parse("[" + five + "]")).isPresent();
    }

    @Test
    void zeroOrSixRejected() {
        String six = IntStream.rangeClosed(1, 6).mapToObj(i -> single("q" + i)).collect(Collectors.joining(","));

        assertThat(QaQuestions.parse("[]")).isEmpty();
        assertThat(QaQuestions.parse("[" + six + "]")).isEmpty();
    }

    @Test
    void duplicateOrMalformedIdRejected() {
        assertThat(QaQuestions.parse(array(single("q1"), single("q1")))).isEmpty();
        assertThat(QaQuestions.parse(array(single("")))).isEmpty();
        assertThat(QaQuestions.parse(array(single("Q1")))).isEmpty();
        assertThat(QaQuestions.parse(array(single("a-b")))).isEmpty();
        assertThat(QaQuestions.parse(array(single("a".repeat(21))))).isEmpty();
        assertThat(QaQuestions.parse(array(single("a".repeat(20))))).isPresent();
        assertThat(QaQuestions.parse("[{\"type\":\"text\",\"question\":\"Who?\"}]")).isEmpty();
        assertThat(QaQuestions.parse("[{\"id\":7,\"type\":\"text\",\"question\":\"Who?\"}]")).isEmpty();
    }

    @Test
    void unknownTypeRejected() {
        assertThat(QaQuestions.parse(array(question("rating", "[\"a\",\"b\"]")))).isEmpty();
        assertThat(QaQuestions.parse(array(question("SINGLE", "[\"a\",\"b\"]")))).isEmpty();
        assertThat(QaQuestions.parse("[{\"id\":\"q1\",\"question\":\"Who?\"}]")).isEmpty();
    }

    @Test
    void questionOver300Rejected() {
        String longText = "{\"id\":\"q1\",\"type\":\"text\",\"question\":\"" + "x".repeat(301) + "\"}";
        String limit = "{\"id\":\"q1\",\"type\":\"text\",\"question\":\"" + "x".repeat(300) + "\"}";
        String empty = "{\"id\":\"q1\",\"type\":\"text\",\"question\":\"\"}";
        String missing = "{\"id\":\"q1\",\"type\":\"text\"}";

        assertThat(QaQuestions.parse(array(longText))).isEmpty();
        assertThat(QaQuestions.parse(array(limit))).isPresent();
        assertThat(QaQuestions.parse(array(empty))).isEmpty();
        assertThat(QaQuestions.parse(array(missing))).isEmpty();
    }

    @Test
    void singleOrMultiWithOneOrSevenOptionsRejected() {
        String seven = "[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\",\"7\"]";

        for (String type : List.of("single", "multi")) {
            assertThat(QaQuestions.parse(array(question(type, "[\"a\"]")))).isEmpty();
            assertThat(QaQuestions.parse(array(question(type, "[]")))).isEmpty();
            assertThat(QaQuestions.parse(array(question(type, null)))).isEmpty();
            assertThat(QaQuestions.parse(array(question(type, seven)))).isEmpty();
            assertThat(QaQuestions.parse(array(question(type, "[\"1\",\"2\"]")))).isPresent();
            assertThat(QaQuestions.parse(array(question(type, "[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\"]")))).isPresent();
            assertThat(QaQuestions.parse(array(question(type, "\"a,b\"")))).isEmpty();
            assertThat(QaQuestions.parse(array(question(type, "[\"a\",2]")))).isEmpty();
        }
    }

    @Test
    void duplicateOptionRejected() {
        assertThat(QaQuestions.parse(array(question("single", "[\"a\",\"b\",\"a\"]")))).isEmpty();
    }

    @Test
    void optionOver120Rejected() {
        assertThat(QaQuestions.parse(array(question("single", "[\"a\",\"" + "x".repeat(121) + "\"]")))).isEmpty();
        assertThat(QaQuestions.parse(array(question("single", "[\"a\",\"" + "x".repeat(120) + "\"]")))).isPresent();
        assertThat(QaQuestions.parse(array(question("single", "[\"a\",\"\"]")))).isEmpty();
    }

    @Test
    void textWithOptionsRejected() {
        assertThat(QaQuestions.parse(array(question("text", "[\"a\",\"b\"]")))).isEmpty();
        assertThat(QaQuestions.parse(array(question("text", "[]")))).isPresent();
        assertThat(QaQuestions.parse(array(question("text", "null")))).isPresent();
        assertThat(QaQuestions.parse(array(question("text", null)))).isPresent();
    }

    @Test
    void nonJsonOrNonArrayRejected() {
        assertThat(QaQuestions.parse(null)).isEmpty();
        assertThat(QaQuestions.parse("")).isEmpty();
        assertThat(QaQuestions.parse("not json")).isEmpty();
        assertThat(QaQuestions.parse("{\"id\":\"q1\",\"type\":\"text\",\"question\":\"Who?\"}")).isEmpty();
        assertThat(QaQuestions.parse("[\"q1\"]")).isEmpty();
        assertThat(QaQuestions.parse(array(single("q1")) + " trailing")).isEmpty();
    }

    @Test
    void standardIsTheMarker() {
        assertThat(QaQuestions.isStandard(QaQuestions.STANDARD_MARKER)).isTrue();
        assertThat(QaQuestions.isStandard("{ \"standard\" : true }")).isTrue();
        assertThat(QaQuestions.isStandard(array(single("q1")))).isFalse();
        assertThat(QaQuestions.isStandard("{\"standard\":false}")).isFalse();
        assertThat(QaQuestions.isStandard("{\"standard\":\"true\"}")).isFalse();
        assertThat(QaQuestions.isStandard(null)).isFalse();
        assertThat(QaQuestions.isStandard("not json")).isFalse();
        assertThat(QaQuestions.parse(QaQuestions.STANDARD_MARKER)).isEmpty();
    }
}
