package com.flowcortex.chat.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.chat.conversation.ConversationStore;
import com.flowcortex.chat.conversation.ConversationStore.StoredConversation;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChatTopicAuthorizerTest {

    private final ConversationStore store = mock(ConversationStore.class);
    private final ChatTopicAuthorizer authorizer = new ChatTopicAuthorizer(store);

    @Test
    void anOwnConversationTopicIsClaimedAndAuthorized() {
        UUID owner = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        when(store.find(owner, conversation)).thenReturn(Optional.of(
                new StoredConversation(conversation, "t", Instant.now(), Instant.now())));

        assertThat(authorizer.supports("chat:" + conversation)).isTrue();
        assertThat(authorizer.authorize(owner, "chat:" + conversation)).isTrue();
    }

    @Test
    void aForeignOrMissingConversationIsClaimedButNotAuthorized() {
        UUID conversation = UUID.randomUUID();
        when(store.find(any(), any())).thenReturn(Optional.empty());

        assertThat(authorizer.supports("chat:" + conversation)).isTrue();
        assertThat(authorizer.authorize(UUID.randomUUID(), "chat:" + conversation)).isFalse();
    }

    @Test
    void onlyTheCanonicalLowercaseChatFormIsClaimed() {
        UUID id = UUID.randomUUID();

        assertThat(authorizer.supports("chat:not-a-uuid")).isFalse();
        assertThat(authorizer.supports("chat:")).isFalse();
        assertThat(authorizer.supports("chat:" + id.toString().toUpperCase())).isFalse();
        assertThat(authorizer.supports("run:" + id)).isFalse();
        assertThat(authorizer.supports("user")).isFalse();
        assertThat(authorizer.supports(null)).isFalse();
    }

    @Test
    void anUnclaimedTopicIsNeverAuthorizedAndNeverReachesTheStore() {
        assertThat(authorizer.authorize(UUID.randomUUID(), "chat:not-a-uuid")).isFalse();
        assertThat(authorizer.authorize(UUID.randomUUID(), null)).isFalse();

        verify(store, never()).find(any(), any());
    }
}
