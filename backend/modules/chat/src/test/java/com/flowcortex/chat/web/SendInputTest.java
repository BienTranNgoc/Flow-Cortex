package com.flowcortex.chat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.chat.cards.Attachment;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class SendInputTest {

    private static ChatProblemException refusal(Runnable call) {
        try {
            call.run();
        } catch (ChatProblemException e) {
            return e;
        }
        throw new AssertionError("expected a refusal");
    }

    @Test
    void anIdempotencyKeyIsTrimmedAndAMissingBlankOrTooLongOneIsRefused() {
        assertThat(SendInput.key("  abc  ")).isEqualTo("abc");

        for (String bad : new String[] {null, "   ", "k".repeat(SendInput.MAX_KEY_CHARS + 1)}) {
            ChatProblemException refused = refusal(() -> SendInput.key(bad));
            assertThat(refused.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(refused.fieldErrors()).containsOnlyKeys("Idempotency-Key");
        }
        assertThat(SendInput.key("k".repeat(SendInput.MAX_KEY_CHARS))).hasSize(SendInput.MAX_KEY_CHARS);
    }

    @Test
    void aMessageTextIsTrimmedAndCountedInCodePoints() {
        SendInput.Message message = SendInput.message("  xin chào  ", null);

        assertThat(message.text()).isEqualTo("xin chào");
        assertThat(message.attachments()).isEmpty();
        assertThat(SendInput.message("😀".repeat(SendInput.MAX_TEXT_CHARS), null).text())
                .hasSize(SendInput.MAX_TEXT_CHARS * 2);
    }

    @Test
    void aBlankOrTooLongTextIsRefusedOnTheTextField() {
        for (String bad : new String[] {null, "  ", "a".repeat(SendInput.MAX_TEXT_CHARS + 1)}) {
            ChatProblemException refused = refusal(() -> SendInput.message(bad, null));
            assertThat(refused.code()).isEqualTo("validation-failed");
            assertThat(refused.fieldErrors()).containsOnlyKeys("text");
        }
    }

    @Test
    void attachmentNamesAreStrippedAndABadListIsRefusedOnTheAttachmentsField() {
        UUID id = UUID.randomUUID();

        SendInput.Message message = SendInput.message("hi", List.of(new Attachment(id, "  a.png ")));
        assertThat(message.attachments()).containsExactly(new Attachment(id, "a.png"));

        List<Attachment> tooMany = java.util.stream.IntStream.range(0, SendInput.MAX_ATTACHMENTS + 1)
                .mapToObj(i -> new Attachment(UUID.randomUUID(), "n" + i)).toList();
        assertThatThrownBy(() -> SendInput.message("hi", tooMany)).isInstanceOfSatisfying(
                ChatProblemException.class, e -> assertThat(e.fieldErrors()).containsOnlyKeys("attachments"));
    }

    @Test
    void bothTheTextAndTheAttachmentsCanBeRefusedTogether() {
        assertThatThrownBy(() -> SendInput.message("", List.of(new Attachment(null, "a")))).isInstanceOfSatisfying(
                ChatProblemException.class, e -> assertThat(e.fieldErrors()).containsOnlyKeys("text", "attachments"));
    }

    @Test
    void theLanguageIsVietnameseOnlyForAVietnameseRequest() {
        assertThat(SendInput.language(Locale.forLanguageTag("vi"))).isEqualTo("vi");
        assertThat(SendInput.language(Locale.forLanguageTag("vi-VN"))).isEqualTo("vi");
        assertThat(SendInput.language(Locale.FRENCH)).isEqualTo("en");
        assertThat(SendInput.language(Locale.ENGLISH)).isEqualTo("en");
    }
}
