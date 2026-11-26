package com.flowcortex.support.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.support.assistant.HandoffDraft.Draft;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HandoffDraftTest {

    private static final Locale VI = Locale.of("vi");
    private static final Locale EN = Locale.ENGLISH;
    private static final String UUID_CODE = "5f0c2a9e-1b7d-4c3e-9a51-2d8e6f4b7c10";

    @Test
    void aGreetingFirstIsDroppedAndTheCodeIsListed() {
        Draft draft = HandoffDraft.build(
                List.of("xin chào", "Tôi bị trừ tiền hai lần cho run RUN-4821"), VI);

        assertThat(draft.subject()).contains("Tôi bị trừ tiền hai lần cho run RUN-4821");
        assertThat(draft.description())
                .isEqualTo("Tôi bị trừ tiền hai lần cho run RUN-4821\n\nMã liên quan: RUN-4821");
    }

    @Test
    void onlyGreetingsAndAShortLineKeepTheLine() {
        Draft draft = HandoffDraft.build(List.of("hi", "refund"), EN);

        assertThat(draft.subject()).contains("refund");
        assertThat(draft.description()).isEqualTo("refund");
    }

    @Test
    void whenEveryMessageIsAGreetingAllAreKept() {
        Draft draft = HandoffDraft.build(List.of("hello", "xin chào"), VI);

        assertThat(draft.subject()).contains("hello");
        assertThat(draft.description()).isEqualTo("hello\nxin chào");
    }

    @Test
    void onlyTheLatestTenMessagesAreUsed() {
        List<String> messages = new ArrayList<>(List.of("câu hỏi số 1 RUN-0001"));
        IntStream.rangeClosed(2, 14).forEach(n -> messages.add("câu hỏi số " + n));

        Draft draft = HandoffDraft.build(messages, VI);

        assertThat(HandoffDraft.WINDOW).isEqualTo(10);
        assertThat(draft.subject()).contains("câu hỏi số 5");
        assertThat(draft.description().lines().toList())
                .startsWith("câu hỏi số 5", "câu hỏi số 6")
                .endsWith("câu hỏi số 14")
                .hasSize(10)
                .noneMatch(line -> line.contains("RUN-0001"));
    }

    @Test
    void aSubjectUnderFiveCharactersIsAbsent() {
        Draft short4 = HandoffDraft.build(List.of("abcd"), EN);
        Draft exact5 = HandoffDraft.build(List.of("abcde"), EN);

        assertThat(short4.subject()).isEqualTo(Optional.empty());
        assertThat(short4.description()).isEqualTo("abcd");
        assertThat(exact5.subject()).contains("abcde");
    }

    @Test
    void theSubjectIsCutAtOneHundredTwentyCodePointsWithWhitespaceCollapsed() {
        assertThat(HandoffDraft.build(List.of("  Tôi   bị \n lỗi  "), VI).subject()).contains("Tôi bị lỗi");

        String longLine = "x".repeat(119) + "😀😀";
        String subject = HandoffDraft.build(List.of(longLine), VI).subject().orElseThrow();

        assertThat(subject.codePointCount(0, subject.length())).isEqualTo(120);
        assertThat(subject).isEqualTo("x".repeat(119) + "😀");
    }

    @Test
    void theDescriptionIsCutAtEighteenHundredBeforeTheCodes() {
        String first = "a".repeat(1000);
        String second = "b".repeat(1000);
        String third = "c".repeat(400) + " " + UUID_CODE;

        Draft draft = HandoffDraft.build(List.of(first, second, third), EN);

        String joined = first + "\n" + second + "\n" + third;
        assertThat(joined.length()).isGreaterThan(2400);
        assertThat(draft.description())
                .isEqualTo(joined.substring(0, 1800) + "\n\nRelated codes/IDs: " + UUID_CODE);
    }

    @Test
    void codesAreDedupedCaseInsensitivelyInOrderAndCappedAtTwenty() {
        assertThat(HandoffDraft.codes("RUN-4821 then run-4821 and 12345678, again RUN-4821"))
                .containsExactly("RUN-4821", "12345678");

        String many = String.join(" ", IntStream.rangeClosed(1, 25).mapToObj(n -> "RUN-" + n).toList());
        Draft draft = HandoffDraft.build(List.of("Nhiều mã " + many), VI);

        String codesLine = draft.description().substring(draft.description().indexOf("Mã liên quan: "));
        assertThat(codesLine.substring("Mã liên quan: ".length()).split(", "))
                .hasSize(20)
                .startsWith("RUN-1", "RUN-2")
                .endsWith("RUN-20");
    }

    @Test
    void theCodesLabelFollowsTheAnswerLanguage() {
        assertThat(HandoffDraft.build(List.of("lỗi run RUN-4821"), EN).description())
                .endsWith("\n\nRelated codes/IDs: RUN-4821");
        assertThat(HandoffDraft.build(List.of("lỗi run RUN-4821"), VI).description())
                .endsWith("\n\nMã liên quan: RUN-4821");
    }

    @Test
    void uuidPrefixCodeAndLongDigitRunAreCodes() {
        assertThat(HandoffDraft.codes("id " + UUID_CODE + " và RUN-4821 và 12345678"))
                .containsExactly(UUID_CODE, "RUN-4821", "12345678");
        assertThat(HandoffDraft.codes("AB-CD và 1234567")).isEmpty();
        assertThat(HandoffDraft.codes("RUN-4821 run-4821 12345678 AB-CD 1234567"))
                .containsExactly("RUN-4821", "12345678");
    }

    @Test
    void aCodeInsideAUuidIsNotListedOnItsOwn() {
        assertThat(HandoffDraft.codes("2c9f1a40-abcd-ef01-9a51-2d8e6f4b7c10"))
                .containsExactly("2c9f1a40-abcd-ef01-9a51-2d8e6f4b7c10");
    }

    @Test
    void anEmptyConversationGivesAnEmptyDraft() {
        Draft draft = HandoffDraft.build(List.of(), VI);

        assertThat(draft.subject()).isEmpty();
        assertThat(draft.description()).isEmpty();
        assertThat(HandoffDraft.codes(null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"chào bạn", "xin chào", "hello", "hi", "cảm ơn nhé", "", "   ", "?"})
    void greetingsAndSmallTalkAreLowIntent(String message) {
        assertThat(HandoffDraft.lowIntent(message)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"credit tính sao", "tạo workflow mới", "vì sao workflow lỗi", "refund"})
    void aShortRealQuestionKeepsItsContentWord(String message) {
        assertThat(HandoffDraft.lowIntent(message)).isFalse();
    }
}
