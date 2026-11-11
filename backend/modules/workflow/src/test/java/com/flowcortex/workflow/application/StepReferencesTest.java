package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.workflow.application.StepReferences.Field;
import com.flowcortex.workflow.application.StepReferences.Index;
import com.flowcortex.workflow.application.StepReferences.Kind;
import com.flowcortex.workflow.application.StepReferences.Name;
import com.flowcortex.workflow.application.StepReferences.Order;
import com.flowcortex.workflow.application.StepReferences.Problem;
import com.flowcortex.workflow.application.StepReferences.Reference;
import com.flowcortex.workflow.application.StepReferences.Step;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StepReferencesTest {

    private static Step step(int order, String name, String inputRef) {
        return new Step(UUID.randomUUID(), order, name, inputRef);
    }

    private static Kind kindOf(Map<UUID, Problem> problems, Step step) {
        Problem problem = problems.get(step.id());
        return problem == null ? null : problem.kind();
    }

    @Test
    void aDigitHeadIsAnOrderAndAnyOtherHeadIsAName() {
        Reference digits = StepReferences.parse("{step:2}").orElseThrow();
        Reference word = StepReferences.parse("{step:draft}").orElseThrow();

        assertThat(digits.head()).isInstanceOf(Order.class);
        assertThat(((Order) digits.head()).value()).isEqualTo(2);
        assertThat(word.head()).isEqualTo(new Name("draft"));

        List<Step> steps = List.of(step(1, "2", null), step(2, "other", null), step(3, null, "{step:2}"));
        assertThat(StepReferences.resolve(steps.get(2), steps)).hasValue(2);
        assertThat(StepReferences.parse("{step:99999999999}").orElseThrow().head()).isInstanceOf(Order.class);
    }

    @Test
    void aPathHasDotSegmentsAndBracketIndexes() {
        Reference reference = StepReferences.parse("{step:draft.scenes[0].text}").orElseThrow();

        assertThat(reference.head()).isEqualTo(new Name("draft"));
        assertThat(reference.path()).containsExactly(new Field("scenes"), new Index(0), new Field("text"));
    }

    @Test
    void textOutsideTheGrammarIsMalformed() {
        for (String text : List.of(
                "{step: 2}", "{step:draft }", "{step:draft.}", "step:draft", "{step:a.b[x]}", "{step:}",
                "{step:a{b}", "{step:1.a[99999999999]}", "{step:" + "x".repeat(250) + "}")) {
            assertThat(StepReferences.parse(text)).as(text).isEmpty();
            Step reader = step(2, null, text);
            Map<UUID, Problem> problems = StepReferences.analyze(List.of(step(1, "a", null), reader));
            assertThat(kindOf(problems, reader)).as(text).isEqualTo(Kind.MALFORMED);
        }
    }

    @Test
    void aNameWithInnerSpacesIsReadByName() {
        Reference reference = StepReferences.parse("{step:Echo step 2.output}").orElseThrow();

        assertThat(reference.head()).isEqualTo(new Name("Echo step 2"));
        assertThat(reference.path()).containsExactly(new Field("output"));
        assertThat(StepReferences.parse("{step:" + "x".repeat(248) + "}")).isPresent();
        assertThat(StepReferences.parse("{step:" + "x".repeat(249) + "}")).isEmpty();
    }

    @Test
    void analyseReportsUnknownAmbiguousNotEarlierAndMalformedPerStep() {
        Step first = step(1, "draft", "{step:1}");
        Step malformed = step(2, "b", "{step: 1}");
        Step unknownOrder = step(3, "c", "{step:99}");
        Step unknownName = step(4, "d", "{step:script}");
        Step ambiguous = step(5, "e", "{step:twin}");
        Step twinA = step(6, "twin", null);
        Step twinB = step(7, "twin", null);
        Step clean = step(8, "f", "{step:draft.title}");
        Step blank = step(9, "g", "  ");
        Step none = step(10, "h", null);

        Map<UUID, Problem> problems = StepReferences.analyze(
                List.of(first, malformed, unknownOrder, unknownName, ambiguous, twinA, twinB, clean, blank, none));

        assertThat(kindOf(problems, first)).isEqualTo(Kind.NOT_EARLIER);
        assertThat(kindOf(problems, malformed)).isEqualTo(Kind.MALFORMED);
        assertThat(kindOf(problems, unknownOrder)).isEqualTo(Kind.UNKNOWN_STEP);
        assertThat(problems.get(unknownOrder.id()).detail()).isEqualTo("99");
        assertThat(kindOf(problems, unknownName)).isEqualTo(Kind.UNKNOWN_STEP);
        assertThat(problems.get(unknownName.id()).detail()).isEqualTo("script");
        assertThat(kindOf(problems, ambiguous)).isEqualTo(Kind.AMBIGUOUS_NAME);
        assertThat(kindOf(problems, clean)).isNull();
        assertThat(kindOf(problems, blank)).isNull();
        assertThat(kindOf(problems, none)).isNull();
    }

    @Test
    void oneKindPerStepByPrecedence() {
        Step unknownOnFirst = step(1, "a", "{step:script}");
        Step selfOnSecond = step(2, "b", "{step:2}");
        Step twinRead = step(3, "c", "{step:twin}");
        Step twinA = step(4, "twin", null);
        Step twinB = step(5, "twin", null);
        Step laterNamed = step(6, "d", "{step:later}");
        Step later = step(7, "later", null);

        Map<UUID, Problem> problems = StepReferences.analyze(
                List.of(unknownOnFirst, selfOnSecond, twinRead, twinA, twinB, laterNamed, later));

        assertThat(kindOf(problems, unknownOnFirst)).isEqualTo(Kind.UNKNOWN_STEP);
        assertThat(kindOf(problems, selfOnSecond)).isEqualTo(Kind.NOT_EARLIER);
        assertThat(kindOf(problems, twinRead)).isEqualTo(Kind.AMBIGUOUS_NAME);
        assertThat(kindOf(problems, laterNamed)).isEqualTo(Kind.NOT_EARLIER);
        assertThat(problems).hasSize(4);
    }

    @Test
    void canonicalizeTurnsANamedReferenceIntoItsOrderKeepingThePath() {
        List<Step> steps = List.of(step(1, "draft", null), step(2, "voice", "{step:draft.title}"));

        assertThat(StepReferences.canonicalize("{step:draft.title}", steps)).isEqualTo("{step:1.title}");
        assertThat(StepReferences.canonicalize("{step:voice.a[2].b}", steps)).isEqualTo("{step:2.a[2].b}");
        assertThat(StepReferences.canonicalize("{step:1.title}", steps)).isEqualTo("{step:1.title}");
        assertThat(StepReferences.canonicalize(null, steps)).isNull();
        assertThat(StepReferences.canonicalize("", steps)).isEmpty();
    }
}
