package com.flowcortex.workflow.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.workflow.application.QaQuestions.QaQuestion;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Checks a reviewer's answers against a QA task's questions and writes them, with the reviewed
 * content, into the text the next step receives. Pure: no I/O, no request or locale state; the
 * caller passes the wording ({@link Labels}) and the questions already rendered in the request's
 * language.
 */
final class QaAnswers {

    /** Longest answer to a text question, in characters. */
    static final int MAX_TEXT_LENGTH = 2000;

    private QaAnswers() {}

    /** The request language's wording for the section the answers are written under. */
    record Labels(String header, String noAnswer) {}

    /**
     * Field errors keyed by question id (an unknown id keys its own error), empty when every
     * answer fits. A null, blank or empty-list answer counts as no answer. {@code
     * extraAcceptedOptions} are option texts accepted for a choice question besides its own, so a
     * reviewer who switched language between reading and answering the standard questions is not
     * refused.
     *
     * @param answers a JSON object of question id to answer, or null for none
     */
    static Map<String, LocalizedMessage> validate(
            List<QaQuestion> questions, JsonNode answers, Set<String> extraAcceptedOptions) {
        Map<String, LocalizedMessage> errors = new LinkedHashMap<>();
        if (isMissing(answers)) {
            return errors;
        }
        requireObject(answers);
        Map<String, QaQuestion> byId = questions.stream()
                .collect(Collectors.toMap(QaQuestion::id, question -> question, (first, second) -> first));
        answers.fields().forEachRemaining(entry -> {
            QaQuestion question = byId.get(entry.getKey());
            if (question == null) {
                errors.put(entry.getKey(), new LocalizedMessage("reviewTask.answers.unknownQuestion"));
                return;
            }
            fault(question, entry.getValue(), extraAcceptedOptions)
                    .ifPresent(message -> errors.put(question.id(), message));
        });
        return errors;
    }

    /**
     * The reviewed content, then {@code \n\n---\n## <header>\n\n}, then per question in order
     * {@code **<question>**\n<answer>\n\n}; a multiple-choice answer is one {@code - <value>} line
     * per value and an unanswered question reads the no-answer label. Empty content starts with the
     * separator itself. Expects answers that passed {@link #validate}.
     */
    static String compile(String content, List<QaQuestion> questions, JsonNode answers, Labels labels) {
        StringBuilder out = new StringBuilder();
        if (content != null && !content.isEmpty()) {
            out.append(content).append("\n\n");
        }
        out.append("---\n## ").append(labels.header()).append("\n\n");
        for (QaQuestion question : questions) {
            String answer = isMissing(answers) ? null : answerText(answers.get(question.id())).orElse(null);
            out.append("**").append(question.question()).append("**\n")
                    .append(answer == null ? labels.noAnswer() : answer).append("\n\n");
        }
        return out.toString();
    }

    private static Optional<LocalizedMessage> fault(QaQuestion question, JsonNode value, Set<String> extra) {
        if (isMissing(value)) {
            return Optional.empty();
        }
        return switch (question.type()) {
            case SINGLE -> singleFault(question, value, extra);
            case MULTI -> multiFault(question, value, extra);
            case TEXT -> textFault(value);
        };
    }

    private static Optional<LocalizedMessage> singleFault(QaQuestion question, JsonNode value, Set<String> extra) {
        if (!value.isTextual()) {
            return Optional.of(new LocalizedMessage("reviewTask.answers.notAnOption"));
        }
        if (value.textValue().isBlank() || accepted(question, value.textValue(), extra)) {
            return Optional.empty();
        }
        return Optional.of(new LocalizedMessage("reviewTask.answers.notAnOption"));
    }

    private static Optional<LocalizedMessage> multiFault(QaQuestion question, JsonNode value, Set<String> extra) {
        LocalizedMessage notASubset = new LocalizedMessage("reviewTask.answers.notASubset");
        if (!value.isArray()) {
            return Optional.of(notASubset);
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode element : value) {
            if (!element.isTextual() || !accepted(question, element.textValue(), extra)
                    || !seen.add(element.textValue())) {
                return Optional.of(notASubset);
            }
        }
        return Optional.empty();
    }

    private static Optional<LocalizedMessage> textFault(JsonNode value) {
        if (!value.isTextual()) {
            return Optional.of(new LocalizedMessage("reviewTask.answers.notText"));
        }
        String text = value.textValue();
        if (text.codePointCount(0, text.length()) > MAX_TEXT_LENGTH) {
            return Optional.of(new LocalizedMessage("reviewTask.answers.tooLong", MAX_TEXT_LENGTH));
        }
        return Optional.empty();
    }

    /** The answer as written in the compiled text, or empty when the question was left unanswered. */
    private static Optional<String> answerText(JsonNode value) {
        if (isMissing(value)) {
            return Optional.empty();
        }
        if (value.isArray()) {
            String lines = joinLines(value);
            return lines.isEmpty() ? Optional.empty() : Optional.of(lines);
        }
        String text = value.asText().strip();
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }

    private static String joinLines(JsonNode values) {
        StringBuilder lines = new StringBuilder();
        for (JsonNode value : values) {
            if (!lines.isEmpty()) {
                lines.append('\n');
            }
            lines.append("- ").append(value.asText());
        }
        return lines.toString();
    }

    private static boolean accepted(QaQuestion question, String option, Set<String> extra) {
        return question.options().contains(option) || extra.contains(option);
    }

    private static boolean isMissing(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode();
    }

    private static void requireObject(JsonNode answers) {
        if (!answers.isObject()) {
            throw new IllegalArgumentException("Answers must be a JSON object of question id to answer");
        }
    }
}
