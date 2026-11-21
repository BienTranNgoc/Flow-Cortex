package com.flowcortex.chat.cards;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AttachmentsTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private static Attachment file(String name) {
        return new Attachment(UUID.randomUUID(), name);
    }

    @Test
    void aValidListComesBackWithItsNamesStripped() {
        UUID id = UUID.randomUUID();

        assertThat(Attachments.clean(List.of(new Attachment(id, "  a.png  ")), 1, 3))
                .contains(List.of(new Attachment(id, "a.png")));
    }

    @Test
    void aNullListCountsAsNoAttachmentsAndIsAcceptedOnlyWhenNoneAreRequired() {
        assertThat(Attachments.clean(null, 0, 3)).contains(List.of());
        assertThat(Attachments.clean(null, 1, 3)).isEmpty();
    }

    @Test
    void theNumberOfEntriesMustFallBetweenTheBounds() {
        List<Attachment> three = List.of(file("a"), file("b"), file("c"));

        assertThat(Attachments.clean(three, 1, 2)).isEmpty();
        assertThat(Attachments.clean(three, 4, 5)).isEmpty();
        assertThat(Attachments.clean(three, 3, 3)).isPresent();
    }

    @Test
    void aNullEntryAnIdOrNameMissingOrARepeatedIdRefusesTheList() {
        UUID id = UUID.randomUUID();

        assertThat(Attachments.clean(Arrays.asList(file("a"), null), 0, 5)).isEmpty();
        assertThat(Attachments.clean(List.of(new Attachment(null, "a")), 0, 5)).isEmpty();
        assertThat(Attachments.clean(List.of(new Attachment(id, null)), 0, 5)).isEmpty();
        assertThat(Attachments.clean(List.of(new Attachment(id, "a"), new Attachment(id, "b")), 0, 5)).isEmpty();
    }

    @Test
    void aBlankOrTooLongNameRefusesTheList() {
        assertThat(Attachments.clean(List.of(file("   ")), 0, 5)).isEmpty();
        assertThat(Attachments.clean(List.of(file("n".repeat(Attachment.MAX_FILE_NAME_CHARS + 1))), 0, 5)).isEmpty();
        assertThat(Attachments.clean(List.of(file("n".repeat(Attachment.MAX_FILE_NAME_CHARS))), 0, 5)).isPresent();
    }

    @Test
    void thePayloadOfAMessageGivesBackItsAttachments() {
        UUID id = UUID.randomUUID();

        assertThat(Attachments.fromPayload(mapper,
                "{\"attachments\":[{\"uploadId\":\"" + id + "\",\"fileName\":\"a.png\"}]}"))
                .containsExactly(new Attachment(id, "a.png"));
    }

    @Test
    void anAbsentUnreadableOrAttachmentlessPayloadIsEmpty() {
        assertThat(Attachments.fromPayload(mapper, null)).isEmpty();
        assertThat(Attachments.fromPayload(mapper, "{not json")).isEmpty();
        assertThat(Attachments.fromPayload(mapper, "{\"error\":\"x\"}")).isEmpty();
    }

    @Test
    void anEntryThatIsNotAnAttachmentIsSkippedAndTheRestKept() {
        UUID id = UUID.randomUUID();

        assertThat(Attachments.fromNode(mapper.readTree("[{\"uploadId\":\"bad\",\"fileName\":\"x\"},"
                + "{\"uploadId\":\"" + id + "\",\"fileName\":\"ok.png\"}]")))
                .containsExactly(new Attachment(id, "ok.png"));
    }
}
