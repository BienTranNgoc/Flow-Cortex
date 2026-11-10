package com.flowcortex.connector.application.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.connector.api.ConnectorException;
import com.flowcortex.connector.api.ConnectorException.Reason;
import com.flowcortex.connector.api.TelegramMedia;
import com.flowcortex.connector.api.TelegramMedia.Kind;
import com.flowcortex.connector.application.telegram.TelegramRules.Chat;
import com.flowcortex.connector.application.telegram.TelegramRules.Refusal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TelegramRulesTest {

    private static final String SECRET_35 = "AAEabcdefghijklmnopqrstuvwxyz0123456";

    @Test
    void tokenFormatAcceptsDigitsColonAtLeastThirtyCharsAndTrimsSpaces() {
        assertThat(TelegramRules.normalizeToken("123456789:" + SECRET_35)).contains("123456789:" + SECRET_35);
        assertThat(TelegramRules.normalizeToken("  123456789:" + SECRET_35 + " \n")).contains("123456789:" + SECRET_35);
        assertThat(TelegramRules.normalizeToken("123456789:" + "a".repeat(30))).isPresent();
    }

    @Test
    void tokenFormatRefusesMalformedTokens() {
        assertThat(TelegramRules.normalizeToken("abc:123")).isEmpty();
        assertThat(TelegramRules.normalizeToken("123456789:" + "a".repeat(29))).isEmpty();
        assertThat(TelegramRules.normalizeToken("   ")).isEmpty();
        assertThat(TelegramRules.normalizeToken(null)).isEmpty();
        assertThat(TelegramRules.normalizeToken("123456789:" + SECRET_35 + "!")).isEmpty();
    }

    @Test
    void mergeChatsAddsNewIdsLetsTheNewerEntryWinAndNeverDrops() {
        List<Chat> stored = List.of(new Chat(42, "An", "private"), new Chat(-1001, "Team", "group"));
        List<Chat> merged = TelegramRules.mergeChats(stored,
                List.of(new Chat(-1001, "Team renamed", "supergroup"), new Chat(-1002, "News", "channel")));

        assertThat(merged).containsExactly(new Chat(42, "An", "private"),
                new Chat(-1001, "Team renamed", "supergroup"), new Chat(-1002, "News", "channel"));
        assertThat(TelegramRules.mergeChats(stored, List.of())).isEqualTo(stored);
    }

    @Test
    void manualChatDefaultsTitleAndTypeAndKeepsGivenValues() {
        assertThat(TelegramRules.manualChat(-1003L, null, null)).isEqualTo(new Chat(-1003, "Chat -1003", "group"));
        assertThat(TelegramRules.manualChat(5L, "  ", " ")).isEqualTo(new Chat(5, "Chat 5", "group"));
        assertThat(TelegramRules.manualChat(5L, " Ops ", "channel")).isEqualTo(new Chat(5, "Ops", "channel"));
    }

    @Test
    void chatTitleFallsBackFromTitleToFirstNameToUsernameToId() {
        assertThat(TelegramRules.chatTitle(Map.of("id", 1, "title", "Team", "first_name", "A", "username", "u")))
                .isEqualTo("Team");
        assertThat(TelegramRules.chatTitle(Map.of("id", 1, "first_name", "An", "username", "u"))).isEqualTo("An");
        assertThat(TelegramRules.chatTitle(Map.of("id", 1, "username", "an_user"))).isEqualTo("@an_user");
        assertThat(TelegramRules.chatTitle(Map.of("id", 7))).isEqualTo("Chat 7");
    }

    @Test
    void discoverChatsReadsEveryUpdateKindAndKeepsTheLastSeenEntryPerChat() {
        Object updates = List.of(
                Map.of("update_id", 1, "message", Map.of("chat", Map.of("id", -1001, "type", "group", "title", "Team"))),
                Map.of("update_id", 2, "message", Map.of("chat", Map.of("id", 42, "type", "private", "first_name", "An"))),
                Map.of("update_id", 3, "channel_post", Map.of("chat", Map.of("id", -1002, "type", "channel", "title", "News"))),
                Map.of("update_id", 4, "my_chat_member", Map.of("chat", Map.of("id", -1001, "type", "supergroup", "title", "Team 2"))),
                Map.of("update_id", 5, "poll", Map.of("id", "x")));

        assertThat(TelegramRules.discoverChats(updates)).containsExactly(new Chat(-1001, "Team 2", "supergroup"),
                new Chat(42, "An", "private"), new Chat(-1002, "News", "channel"));
        assertThat(TelegramRules.discoverChats(null)).isEmpty();
        assertThat(TelegramRules.discoverChats("nope")).isEmpty();
    }

    @Test
    void resolveChatPrefersTheGivenIdThenTheDefaultThenFailsWithNoChat() {
        assertThat(TelegramRules.resolveChat(7L, 9L)).isEqualTo(7L);
        assertThat(TelegramRules.resolveChat(null, 9L)).isEqualTo(9L);
        assertThatThrownBy(() -> TelegramRules.resolveChat(null, null))
                .isInstanceOfSatisfying(ConnectorException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.INVALID_INPUT);
                    assertThat(e.detail().code()).isEqualTo("connector.telegram.noChat");
                });
    }

    @Test
    void theDefaultChatMustBeAmongTheStoredOnes() {
        List<Chat> chats = List.of(new Chat(42, "An", "private"));
        assertThat(TelegramRules.hasChat(chats, 42)).isTrue();
        assertThat(TelegramRules.hasChat(chats, 777)).isFalse();
    }

    @Test
    void textRulesAcceptUpToFourThousandNinetySixAndAllowBlankOnlyWithMedia() {
        TelegramRules.checkText("hello", false);
        TelegramRules.checkText("x".repeat(4096), false);
        TelegramRules.checkText("", true);
        TelegramRules.checkText(null, true);
        assertTextInvalid(null, false);
        assertTextInvalid("   ", false);
        assertTextInvalid("x".repeat(4097), false);
    }

    @Test
    void mediaUrlMustBeAnAbsoluteHttpOrHttpsUrl() {
        TelegramRules.checkMediaUrl("https://cdn.example.com/a.png");
        TelegramRules.checkMediaUrl("http://cdn.example.com/a.png");
        for (String bad : new String[] {"/files/a.png", "ftp://x.example.com/a", "", null, "https://", "not a url"}) {
            assertThatThrownBy(() -> TelegramRules.checkMediaUrl(bad))
                    .isInstanceOfSatisfying(ConnectorException.class, e -> {
                        assertThat(e.reason()).isEqualTo(Reason.INVALID_INPUT);
                        assertThat(e.detail().code()).isEqualTo("connector.telegram.mediaUrlInvalid");
                    });
        }
    }

    @Test
    void methodFollowsTheMediaKind() {
        assertThat(TelegramRules.method(null)).isEqualTo("sendMessage");
        assertThat(TelegramRules.method(new TelegramMedia(Kind.PHOTO, "https://a.example.com/a"))).isEqualTo("sendPhoto");
        assertThat(TelegramRules.method(new TelegramMedia(Kind.VIDEO, "https://a.example.com/a"))).isEqualTo("sendVideo");
        assertThat(TelegramRules.method(new TelegramMedia(Kind.DOCUMENT, "https://a.example.com/a")))
                .isEqualTo("sendDocument");
    }

    @Test
    void captionIsCutAtOneThousandTwentyFourAndParseModeDefaultsToHtml() {
        assertThat(TelegramRules.caption("x".repeat(1500))).hasSize(1024);
        assertThat(TelegramRules.caption("short")).isEqualTo("short");
        assertThat(TelegramRules.caption(null)).isEmpty();
        assertThat(TelegramRules.parseMode(null)).isEqualTo("HTML");
    }

    @Test
    void recipientLabelNamesTheStoredChatOrFallsBackToTheBareId() {
        List<Chat> chats = List.of(new Chat(-1001, "Team", "group"));
        assertThat(TelegramRules.recipientLabel(chats, -1001)).isEqualTo("Team (-1001)");
        assertThat(TelegramRules.recipientLabel(chats, 999)).isEqualTo("999");
    }

    @Test
    void refusalFollowsTheOrderedTable() {
        assertRefused(403, "Forbidden: bot was blocked by the user", "recipientBlocked");
        assertRefused(401, "Unauthorized: bot was blocked by the user", "recipientBlocked");
        assertRefused(403, "Forbidden: bot was kicked from the group chat", "botNotInChat");
        assertRefused(403, "Forbidden: bot is not a member of the channel chat", "botNotInChat");
        assertRefused(400, "Bad Request: chat not found", "chatNotFound");
        assertRefused(400, "Bad Request: file is too big", "fileTooLarge");
        assertRefused(413, "Request Entity Too Large", "fileTooLarge");
        assertRefused(401, "Unauthorized", "tokenInvalid");
    }

    @Test
    void rateLimitCarriesTheRetryAfterAndIsRetryable() {
        Refusal refusal = TelegramRules.classifyRefusal(429, "Too Many Requests: retry after 7", 7);

        assertThat(refusal.kind()).isEqualTo(Refusal.Kind.RATE_LIMITED);
        assertThat(refusal.retryable()).isTrue();
        assertThat(refusal.retryAfter()).isEqualTo(Duration.ofSeconds(7));
        ConnectorException ex = refusal.exception();
        assertThat(ex.reason()).isEqualTo(Reason.RATE_LIMITED);
        assertThat(ex.detail().code()).isEqualTo("connector.telegram.send.rateLimited");
        assertThat(ex.detail().args()).containsExactly(7L);
        assertThat(ex.retryAfter()).isEqualTo(Duration.ofSeconds(7));
        assertThat(ex.retryable()).isTrue();
    }

    @Test
    void rateLimitWithoutAParameterReadsTheDescriptionThenFallsBackToOneSecond() {
        assertThat(TelegramRules.classifyRefusal(429, "Too Many Requests: retry after 12", null).retryAfter())
                .isEqualTo(Duration.ofSeconds(12));
        assertThat(TelegramRules.classifyRefusal(429, "Too Many Requests", null).retryAfter())
                .isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void serverErrorsAreUpstreamUnavailableAndNotRejected() {
        for (int code : new int[] {500, 502, 503}) {
            Refusal refusal = TelegramRules.classifyRefusal(code, "Bad Gateway", null);
            assertThat(refusal.kind()).isEqualTo(Refusal.Kind.UPSTREAM_UNAVAILABLE);
            assertThat(refusal.exception().reason()).isEqualTo(Reason.UPSTREAM_UNAVAILABLE);
            assertThat(refusal.exception().retryable()).isTrue();
        }
    }

    @Test
    void anythingElseIsRejectedWithTelegramsDescription() {
        Refusal refusal = TelegramRules.classifyRefusal(400, "Bad Request: can't parse entities", null);

        assertThat(refusal.kind()).isEqualTo(Refusal.Kind.REFUSED);
        assertThat(refusal.reason()).isEqualTo("rejected");
        ConnectorException ex = refusal.exception();
        assertThat(ex.reason()).isEqualTo(Reason.REFUSED);
        assertThat(ex.telegramReason()).isEqualTo("rejected");
        assertThat(ex.detail().code()).isEqualTo("connector.telegram.send.rejected");
        assertThat(ex.detail().args()).containsExactly("Bad Request: can't parse entities");
        assertThat(ex.retryable()).isFalse();
    }

    @Test
    void aMissingDescriptionStillClassifiesByCode() {
        assertThat(TelegramRules.classifyRefusal(401, null, null).reason()).isEqualTo("tokenInvalid");
        assertThat(TelegramRules.classifyRefusal(400, null, null).reason()).isEqualTo("rejected");
    }

    private static void assertRefused(int code, String description, String reason) {
        Refusal refusal = TelegramRules.classifyRefusal(code, description, null);
        assertThat(refusal.kind()).isEqualTo(Refusal.Kind.REFUSED);
        assertThat(refusal.reason()).isEqualTo(reason);
        assertThat(refusal.exception().detail().code()).isEqualTo("connector.telegram.send." + reason);
    }

    private static void assertTextInvalid(String text, boolean hasMedia) {
        assertThatThrownBy(() -> TelegramRules.checkText(text, hasMedia))
                .isInstanceOfSatisfying(ConnectorException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.INVALID_INPUT);
                    assertThat(e.detail().code()).isEqualTo("connector.telegram.textInvalid");
                });
    }
}
