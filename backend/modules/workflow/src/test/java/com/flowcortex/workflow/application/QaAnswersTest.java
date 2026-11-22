package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.workflow.application.QaAnswers.Labels;
import com.flowcortex.workflow.application.QaQuestions.QaQuestion;
import com.flowcortex.workflow.application.QaQuestions.Type;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QaAnswersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Labels ENGLISH = new Labels("Human review", "(no answer)");
    private static final Labels VIETNAMESE = new Labels("Người duyệt trả lời", "(không trả lời)");

    private static final List<QaQuestion> STANDARD = List.of(
            new QaQuestion("q1", Type.SINGLE, "Who is the audience for this output?",
                    List.of("Just me", "Colleagues or team", "Client or partner", "Public or wide audience")),
            new QaQuestion("q2", Type.SINGLE, "How detailed should the output be?",
                    List.of("Brief key points", "Medium with explanations", "Detailed with full reasoning")),
            new QaQuestion("q3", Type.TEXT, "Any specific requirements or constraints? (optional)", List.of()));

    private static final List<QaQuestion> WITH_MULTI = List.of(
            new QaQuestion("m1", Type.MULTI, "Which channels?", List.of("Email", "Social", "Print")),
            new QaQuestion("t1", Type.TEXT, "Notes?", List.of()));

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static Map<String, LocalizedMessage> validate(List<QaQuestion> questions, String answers) {
        return QaAnswers.validate(questions, json(answers), Set.of());
    }

    @Test
    void everyAnswerIsOptional() {
        assertThat(validate(STANDARD, "{}")).isEmpty();
        assertThat(validate(STANDARD, "{\"q1\":null,\"q2\":\"\",\"q3\":\"   \"}")).isEmpty();
        assertThat(validate(WITH_MULTI, "{\"m1\":[]}")).isEmpty();
        assertThat(validate(STANDARD, "{\"q1\":\"Just me\"}")).isEmpty();
        assertThat(QaAnswers.validate(STANDARD, null, Set.of())).isEmpty();
    }

    @Test
    void unknownIdIsAFieldError() {
        Map<String, LocalizedMessage> errors = validate(STANDARD, "{\"q9\":\"x\",\"q1\":\"Just me\"}");

        assertThat(errors).containsOnlyKeys("q9");
        assertThat(errors.get("q9").code()).isEqualTo("reviewTask.answers.unknownQuestion");
    }

    @Test
    void singleOutsideOptionsIsAFieldError() {
        Map<String, LocalizedMessage> errors = validate(STANDARD, "{\"q1\":\"Everyone\",\"q2\":[\"Brief key points\"]}");

        assertThat(errors).containsOnlyKeys("q1", "q2");
        assertThat(errors.get("q1").code()).isEqualTo("reviewTask.answers.notAnOption");
        assertThat(errors.get("q2").code()).isEqualTo("reviewTask.answers.notAnOption");
        assertThat(validate(STANDARD, "{\"q1\":\"just me\"}")).containsOnlyKeys("q1");
    }

    @Test
    void multiMustBeADistinctSubset() {
        assertThat(validate(WITH_MULTI, "{\"m1\":[\"Email\",\"Print\"]}")).isEmpty();
        for (String bad : List.of("\"Email\"", "[\"Email\",\"Email\"]", "[\"Email\",\"Radio\"]", "[\"Email\",3]",
                "[[\"Email\"]]", "7")) {
            Map<String, LocalizedMessage> errors = validate(WITH_MULTI, "{\"m1\":" + bad + "}");

            assertThat(errors).as(bad).containsOnlyKeys("m1");
            assertThat(errors.get("m1").code()).isEqualTo("reviewTask.answers.notASubset");
        }
    }

    @Test
    void textOver2000IsAFieldError() {
        assertThat(validate(STANDARD, "{\"q3\":\"" + "x".repeat(2000) + "\"}")).isEmpty();

        Map<String, LocalizedMessage> errors = validate(STANDARD, "{\"q3\":\"" + "x".repeat(2001) + "\"}");

        assertThat(errors).containsOnlyKeys("q3");
        assertThat(errors.get("q3").code()).isEqualTo("reviewTask.answers.tooLong");
        assertThat(errors.get("q3").args()).containsExactly(2000);
    }

    @Test
    void nonTextForTextIsAFieldError() {
        for (String bad : List.of("5", "true", "[\"a\"]", "[]", "{\"a\":1}")) {
            Map<String, LocalizedMessage> errors = validate(STANDARD, "{\"q3\":" + bad + "}");

            assertThat(errors).as(bad).containsOnlyKeys("q3");
            assertThat(errors.get("q3").code()).isEqualTo("reviewTask.answers.notText");
        }
    }

    @Test
    void standardAnswerAcceptedInEitherLanguage() {
        Set<String> vietnameseOptions = Set.of("Chỉ mình tôi", "Ngắn gọn, nêu ý chính");

        assertThat(QaAnswers.validate(STANDARD, json("{\"q1\":\"Chỉ mình tôi\",\"q2\":\"Ngắn gọn, nêu ý chính\"}"),
                vietnameseOptions)).isEmpty();
        assertThat(QaAnswers.validate(STANDARD, json("{\"q1\":\"Chỉ tôi\"}"), vietnameseOptions)).containsOnlyKeys("q1");
        assertThat(validate(STANDARD, "{\"q1\":\"Chỉ mình tôi\"}")).containsOnlyKeys("q1");
    }

    @Test
    void compileMatchesTheSpecifiedScenario() {
        String compiled = QaAnswers.compile("Draft post", STANDARD, json("{\"q1\":\"Just me\"}"), ENGLISH);

        assertThat(compiled).isEqualTo("Draft post\n\n---\n## Human review\n\n"
                + "**Who is the audience for this output?**\nJust me\n\n"
                + "**How detailed should the output be?**\n(no answer)\n\n"
                + "**Any specific requirements or constraints? (optional)**\n(no answer)\n\n");
    }

    @Test
    void compileWritesMultiAsDashLinesAndVietnameseLabels() {
        String compiled = QaAnswers.compile("Bài nháp", WITH_MULTI,
                json("{\"m1\":[\"Email\",\"Print\"],\"t1\":\"  Dưới 100 từ  \"}"), VIETNAMESE);

        assertThat(compiled).isEqualTo("Bài nháp\n\n---\n## Người duyệt trả lời\n\n"
                + "**Which channels?**\n- Email\n- Print\n\n"
                + "**Notes?**\nDưới 100 từ\n\n");
        assertThat(QaAnswers.compile("x", WITH_MULTI, json("{}"), VIETNAMESE))
                .endsWith("**Which channels?**\n(không trả lời)\n\n**Notes?**\n(không trả lời)\n\n");
    }

    @Test
    void emptyContentStartsWithTheSeparator() {
        assertThat(QaAnswers.compile("", WITH_MULTI, json("{}"), ENGLISH)).startsWith("---\n## Human review\n\n");
        assertThat(QaAnswers.compile(null, WITH_MULTI, null, ENGLISH)).startsWith("---\n## Human review\n\n")
                .endsWith("**Notes?**\n(no answer)\n\n");
    }
}
