package com.flowcortex.chat.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.chat.conversation.ConversationQuery.FolderFilter;
import com.flowcortex.chat.conversation.ConversationQuery.Pinned;
import com.flowcortex.chat.conversation.ListCursor.Sort;
import com.flowcortex.chat.web.ChatProblemException;
import com.flowcortex.shared.web.LocalizedMessage;
import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ConversationQueryTest {

    private static final UUID FOLDER = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");

    private static ConversationQuery parse(String limit, String cursor, String sort, String pinned, String folder,
            String q) {
        return ConversationQuery.parse(limit, cursor, sort, pinned, folder, q);
    }

    private static ConversationQuery search(String q) {
        return parse(null, null, null, null, null, q);
    }

    private static Map<String, LocalizedMessage> refused(Runnable call) {
        try {
            call.run();
        } catch (ChatProblemException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.code()).isEqualTo("validation-failed");
            return e.fieldErrors();
        }
        throw new AssertionError("expected a refusal");
    }

    @Test
    void withNothingGivenTheListIsTheFirstThirtyNewestFirstUnfiltered() {
        ConversationQuery query = parse(null, null, null, null, null, null);

        assertThat(query.limit()).isEqualTo(30);
        assertThat(query.sort()).isEqualTo(Sort.NEWEST);
        assertThat(query.cursor()).isNull();
        assertThat(query.pinned()).isEqualTo(Pinned.ANY);
        assertThat(query.folder()).isEqualTo(FolderFilter.ANY);
        assertThat(query.words()).isEmpty();
    }

    @Test
    void aLimitOutsideOneToAHundredOrNotAWholeNumberIsRefusedOnLimit() {
        for (String bad : new String[] {"0", "101", "-1", "abc", "", "1.5"}) {
            Map<String, LocalizedMessage> errors = refused(() -> parse(bad, null, null, null, null, null));

            assertThat(errors).as(bad).containsOnlyKeys("limit");
            assertThat(errors.get("limit").code()).isEqualTo("chat.list.limit.invalid");
        }
        assertThat(parse("1", null, null, null, null, null).limit()).isEqualTo(1);
        assertThat(parse("100", null, null, null, null, null).limit()).isEqualTo(100);
    }

    @Test
    void sortIsNewestOrOldestAndAnythingElseIsRefusedOnSort() {
        assertThat(parse(null, null, "oldest", null, null, null).sort()).isEqualTo(Sort.OLDEST);
        assertThat(parse(null, null, "newest", null, null, null).sort()).isEqualTo(Sort.NEWEST);

        Map<String, LocalizedMessage> errors = refused(() -> parse(null, null, "latest", null, null, null));

        assertThat(errors).containsOnlyKeys("sort");
        assertThat(errors.get("sort").code()).isEqualTo("chat.list.filter.invalid");
    }

    @Test
    void pinnedIsOnlyOrExcludeAndAnythingElseIsRefusedOnPinned() {
        assertThat(parse(null, null, null, "only", null, null).pinned()).isEqualTo(Pinned.ONLY);
        assertThat(parse(null, null, null, "exclude", null, null).pinned()).isEqualTo(Pinned.EXCLUDE);

        Map<String, LocalizedMessage> errors = refused(() -> parse(null, null, null, "maybe", null, null));

        assertThat(errors).containsOnlyKeys("pinned");
        assertThat(errors.get("pinned").code()).isEqualTo("chat.list.filter.invalid");
    }

    @Test
    void folderIsAUuidOrNoneAndAnythingElseIsRefusedOnFolder() {
        assertThat(parse(null, null, null, null, "none", null).folder()).isEqualTo(FolderFilter.NONE);
        assertThat(parse(null, null, null, null, FOLDER.toString(), null).folder())
                .isEqualTo(new FolderFilter(false, FOLDER));

        for (String bad : new String[] {"abc", "1-1-1-1-1", "NONE", ""}) {
            Map<String, LocalizedMessage> errors = refused(() -> parse(null, null, null, null, bad, null));

            assertThat(errors).as(bad).containsOnlyKeys("folder");
            assertThat(errors.get("folder").code()).isEqualTo("chat.list.filter.invalid");
        }
    }

    @Test
    void aCursorIsDecodedForTheRequestedSortAndATamperedOneIsRefusedOnCursor() {
        ListCursor made = new ListCursor(Sort.OLDEST, Instant.parse("2026-11-08T10:15:30.123456Z"), FOLDER);

        assertThat(parse(null, made.encode(), "oldest", null, null, null).cursor()).isEqualTo(made);
        for (String bad : new String[] {"abc", made.encode()}) {
            String sort = bad.equals("abc") ? "oldest" : "newest";
            Map<String, LocalizedMessage> errors = refused(() -> parse(null, bad, sort, null, null, null));

            assertThat(errors).as(bad).containsOnlyKeys("cursor");
            assertThat(errors.get("cursor").code()).isEqualTo("chat.list.cursor.invalid");
        }
    }

    @Test
    void everyRefusedFieldIsReportedTogether() {
        Map<String, LocalizedMessage> errors = refused(() -> parse("0", null, "x", "y", "z", "q".repeat(101)));

        assertThat(errors).containsOnlyKeys("limit", "sort", "pinned", "folder", "q");
    }

    @Test
    void aBlankOrControlOnlySearchMeansNoWords() {
        assertThat(search(null).words()).isEmpty();
        assertThat(search("   ").words()).isEmpty();
        assertThat(search(" \u0000\u0007 ").words()).isEmpty();
    }

    @Test
    void controlCharactersAreRemovedAndTheRestIsSplitOnWhitespace() {
        assertThat(search("  vi\u0000deo \t dich\n ").words()).containsExactly("video", "dich");
    }

    @Test
    void aSearchOfAHundredCharactersIsKeptAndOneOverIsRefusedOnQ() {
        assertThat(search("a".repeat(100)).words()).hasSize(1);
        assertThat(search("  " + "a".repeat(100) + "  ").words()).hasSize(1);

        Map<String, LocalizedMessage> errors = refused(() -> search("a".repeat(101)));

        assertThat(errors).containsOnlyKeys("q");
        assertThat(errors.get("q").code()).isEqualTo("chat.list.q.tooLong");
    }

    @Test
    void theLengthCountsCharactersNotUtf16Units() {
        assertThat(search("😀".repeat(100)).words()).hasSize(1);
    }

    @Test
    void wordsAreDeduplicatedIgnoringCaseAndOnlyTheFirstEightKept() {
        assertThat(search("Video VIDEO video dich").words()).containsExactly("Video", "dich");
        assertThat(search("a b c d e f g h i j").words()).containsExactly("a", "b", "c", "d", "e", "f", "g", "h");
        assertThat(search("a A b B c C d D e f g h i").words()).containsExactly("a", "b", "c", "d", "e", "f", "g", "h");
    }

    @Test
    void wordsAreNormalizedToNfc() {
        String decomposed = Normalizer.normalize("Quảng", Normalizer.Form.NFD);

        assertThat(decomposed).isNotEqualTo("Quảng");
        assertThat(search(decomposed).words()).isEqualTo(List.of("Quảng"));
    }
}
