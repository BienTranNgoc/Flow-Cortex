package com.flowcortex.support.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.support.assistant.ConversationStore.StoredMessage;
import com.flowcortex.support.web.SupportProblemException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;

class ConversationStoreTest {

    private static final Instant NOW = Instant.parse("2026-10-19T08:20:00Z");
    private static final StoredMessage QUESTION = new StoredMessage("user", "q", null, List.of(), NOW);
    private static final StoredMessage ANSWER = new StoredMessage("assistant", "a", "answered", List.of(), NOW);

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final ConversationStore store = new ConversationStore(jdbcTemplate);

    @Test
    void continuingAConversationTouchesItOnlyForItsOwner() {
        Asker.Member member = new Asker.Member(UUID.randomUUID());
        UUID conversation = UUID.randomUUID();
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        assertThat(store.append(member, conversation, QUESTION, ANSWER)).isEqualTo(conversation);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sql.capture(), args.capture());
        assertThat(sql.getValue()).contains("WHERE id = ? AND owner_user_id = ?");
        assertThat(args.getValue()).endsWith(conversation, member.userId());
    }

    @Test
    void aConversationGoneOrNotOwnedIsNotFoundAndGetsNoMessages() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);

        assertThatThrownBy(() -> store.append(new Asker.Guest("s", "n"), UUID.randomUUID(), QUESTION, ANSWER))
                .isInstanceOfSatisfying(SupportProblemException.class,
                        e -> assertThat(e.code()).isEqualTo("conversation-not-found"));
        verify(jdbcTemplate, never()).update(any(PreparedStatementCreator.class));
    }
}
