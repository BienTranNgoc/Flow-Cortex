package com.flowcortex.notification.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationRequestedTest {

    private final UUID member = UUID.randomUUID();

    private NotificationRequested request(String type, String titleCode, List<Object> args, String link) {
        return new NotificationRequested(UUID.randomUUID(), member, type, titleCode, null, args, link);
    }

    @Test
    void aWellFormedRequestIsBuilt() {
        NotificationRequested built = request("welcome", "notification.welcome.title", List.of(), "/workflows");

        assertThat(built.type()).isEqualTo("welcome");
        assertThat(built.link()).isEqualTo("/workflows");
        assertThat(NotificationRequested.of(member, "welcome", "notification.welcome.title").id()).isNotNull();
    }

    @Test
    void linksThatLeaveTheAppAreRefused() {
        List<String> links = List.of("https://evil.example", "//evil.example", "/\\evil.example",
                "javascript:alert(1)", "/a\tb", "/a b", "/" + "a".repeat(512));

        for (String link : links) {
            assertThatThrownBy(() -> request("welcome", "notification.welcome.title", List.of(), link))
                    .as(link)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("link");
        }
    }

    @Test
    void aNullLinkIsAllowed() {
        assertThat(request("welcome", "notification.welcome.title", List.of(), null).link()).isNull();
        assertThat(request("welcome", "notification.welcome.title", List.of(), "/" + "a".repeat(511)).link())
                .hasSize(512);
    }

    @Test
    void typesOutsideThePatternAreRefused() {
        for (String type : new String[] {"Welcome!", "", "a".repeat(65), null}) {
            assertThatThrownBy(() -> request(type, "notification.welcome.title", List.of(), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("type");
        }
    }

    @Test
    void blankOrMalformedCodesAreRefused() {
        for (String code : new String[] {" ", "", "has space", "x".repeat(129), null}) {
            assertThatThrownBy(() -> request("welcome", code, List.of(), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("titleCode");
        }
        assertThatThrownBy(() -> new NotificationRequested(UUID.randomUUID(), member, "welcome", "a.b", "bad code",
                List.of(), null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bodyCode");
    }

    @Test
    void argumentsAreLimitedToEightScalars() {
        List<Object> nine = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            nine.add("x");
        }
        assertThatThrownBy(() -> request("welcome", "a.b", nine, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("args");
        assertThatThrownBy(() -> request("welcome", "a.b", List.of(Map.of("k", "v")), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("args");
        assertThat(request("welcome", "a.b", List.of("x", 3, true), null).args()).containsExactly("x", 3, true);
    }

    @Test
    void aLongStringArgumentIsTruncatedNotRefused() {
        String exact = "a".repeat(200);

        List<Object> args = request("welcome", "a.b", List.of("b".repeat(201), exact), null).args();

        assertThat(args.get(0)).isEqualTo("b".repeat(199) + "…");
        assertThat((String) args.get(0)).hasSize(200);
        assertThat(args.get(1)).isEqualTo(exact);
    }

    @Test
    void theArgumentListIsCopied() {
        List<Object> source = new ArrayList<>(List.of("x"));

        NotificationRequested built = request("welcome", "a.b", source, null);
        source.add("y");

        assertThat(built.args()).containsExactly("x");
    }
}
