package com.flowcortex.realtime.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.realtime.api.TopicAuthorizer;
import com.flowcortex.realtime.application.TopicAccess.Outcome;
import com.flowcortex.realtime.application.TopicAccess.Status;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.Test;

class TopicAccessTest {

    private static final int MAX_TOPICS = 8;
    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String CHAT = "chat:22222222-2222-2222-2222-222222222222";

    private static TopicAuthorizer chatAuthorizer(BiPredicate<UUID, String> allow) {
        return new TopicAuthorizer() {
            @Override
            public boolean supports(String topic) {
                return topic.startsWith("chat:");
            }

            @Override
            public boolean authorize(UUID userId, String topic) {
                return allow.test(userId, topic);
            }
        };
    }

    private static Outcome resolve(List<String> raw, TopicAuthorizer... authorizers) {
        return TopicAccess.resolve(USER, raw, MAX_TOPICS, List.of(authorizers));
    }

    @Test
    void userMapsToTheCallersOwnTopic() {
        Outcome outcome = resolve(List.of("user"));

        assertThat(outcome.status()).isEqualTo(Status.OK);
        assertThat(outcome.topics()).containsExactly("user:" + USER);
    }

    @Test
    void aTopicNoAuthorizerClaimsIsInvalid() {
        assertThat(resolve(List.of(CHAT)).status()).isEqualTo(Status.INVALID);
    }

    @Test
    void aTopicClaimedAndAuthorizedIsValid() {
        Outcome outcome = resolve(List.of(CHAT), chatAuthorizer((user, topic) -> true));

        assertThat(outcome.status()).isEqualTo(Status.OK);
        assertThat(outcome.topics()).containsExactly(CHAT);
    }

    @Test
    void aClaimedTopicTheAuthorizerRefusesIsNotFound() {
        assertThat(resolve(List.of(CHAT), chatAuthorizer((user, topic) -> false)).status())
                .isEqualTo(Status.NOT_FOUND);
    }

    @Test
    void theAuthorizerIsAskedAboutTheCaller() {
        List<UUID> asked = new ArrayList<>();

        resolve(List.of(CHAT), chatAuthorizer((user, topic) -> asked.add(user)));

        assertThat(asked).containsExactly(USER);
    }

    @Test
    void anEmptyListIsInvalid() {
        assertThat(resolve(List.of()).status()).isEqualTo(Status.INVALID);
    }

    @Test
    void anotherUsersTopicShapeIsInvalid() {
        assertThat(resolve(List.of("user:42")).status()).isEqualTo(Status.INVALID);
    }

    @Test
    void aTopicOverThe128CharacterBoundIsInvalid() {
        TopicAuthorizer greedy = chatAuthorizer((user, topic) -> true);
        String atBound = "chat:" + "a".repeat(123);

        assertThat(resolve(List.of(atBound), greedy).status()).isEqualTo(Status.OK);
        assertThat(resolve(List.of(atBound + "a"), greedy).status()).isEqualTo(Status.INVALID);
    }

    @Test
    void aTopicWithACharacterOutsideTheCharsetIsInvalid() {
        TopicAuthorizer greedy = chatAuthorizer((user, topic) -> true);

        assertThat(resolve(List.of("chat:a b"), greedy).status()).isEqualTo(Status.INVALID);
        assertThat(resolve(List.of("chat:a/b"), greedy).status()).isEqualTo(Status.INVALID);
    }

    @Test
    void oneMoreTopicThanTheLimitIsInvalid() {
        TopicAuthorizer greedy = chatAuthorizer((user, topic) -> true);
        List<String> atLimit = new ArrayList<>();
        for (int i = 0; i < MAX_TOPICS; i++) {
            atLimit.add("chat:" + i);
        }
        List<String> overLimit = new ArrayList<>(atLimit);
        overLimit.add("chat:over");

        assertThat(resolve(atLimit, greedy).status()).isEqualTo(Status.OK);
        assertThat(resolve(overLimit, greedy).status()).isEqualTo(Status.INVALID);
    }

    @Test
    void oneBadTopicRefusesTheWholeRequest() {
        TopicAuthorizer refusing = chatAuthorizer((user, topic) -> false);

        assertThat(resolve(List.of("user", CHAT), refusing).status()).isEqualTo(Status.NOT_FOUND);
        assertThat(resolve(List.of("user", CHAT), refusing).topics()).isEmpty();
        assertThat(resolve(List.of("user", "user:42")).status()).isEqualTo(Status.INVALID);
    }

    @Test
    void duplicatesCollapse() {
        Outcome outcome = resolve(List.of("user", "user", CHAT, CHAT), chatAuthorizer((user, topic) -> true));

        assertThat(outcome.topics()).containsExactlyInAnyOrder("user:" + USER, CHAT);
    }
}
