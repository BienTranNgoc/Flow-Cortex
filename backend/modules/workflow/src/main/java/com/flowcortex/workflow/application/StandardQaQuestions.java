package com.flowcortex.workflow.application;

import com.flowcortex.workflow.application.QaQuestions.QaQuestion;
import com.flowcortex.workflow.application.QaQuestions.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.context.MessageSource;

/**
 * The questions every QA review falls back to, worded per request from the message bundles under
 * {@code qaReview.standard.<id>} and {@code qaReview.standard.<id>.o<n>}. A task stores only
 * {@link QaQuestions#STANDARD_MARKER}, so the same task reads in whichever language its reader uses.
 */
final class StandardQaQuestions {

    private static final List<Locale> LANGUAGES = List.of(Locale.ENGLISH, Locale.of("vi"));

    private record Shape(String id, Type type, int options) {}

    private static final List<Shape> SHAPES = List.of(
            new Shape("q1", Type.SINGLE, 4),
            new Shape("q2", Type.SINGLE, 3),
            new Shape("q3", Type.TEXT, 0));

    private StandardQaQuestions() {}

    static List<QaQuestion> in(MessageSource messages, Locale locale) {
        List<QaQuestion> questions = new ArrayList<>(SHAPES.size());
        for (Shape shape : SHAPES) {
            String key = "qaReview.standard." + shape.id();
            List<String> options = new ArrayList<>(shape.options());
            for (int option = 1; option <= shape.options(); option++) {
                options.add(messages.getMessage(key + ".o" + option, null, locale));
            }
            questions.add(new QaQuestion(shape.id(), shape.type(), messages.getMessage(key, null, locale), options));
        }
        return List.copyOf(questions);
    }

    /** Every option text of the standard questions in every supported language, for a reader who switched language. */
    static Set<String> optionsInEveryLanguage(MessageSource messages) {
        Set<String> options = new HashSet<>();
        for (Locale language : LANGUAGES) {
            for (QaQuestion question : in(messages, language)) {
                options.addAll(question.options());
            }
        }
        return Set.copyOf(options);
    }
}
