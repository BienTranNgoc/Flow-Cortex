package com.flowcortex.user.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;

class JdbcAdminAuditWriterTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"), ZoneOffset.UTC);
    private static final UUID ACTOR = UUID.randomUUID();

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final JdbcAdminAuditWriter writer = new JdbcAdminAuditWriter(jdbc, FIXED, transactions);
    private final Logger writerLog = (Logger) LoggerFactory.getLogger(JdbcAdminAuditWriter.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        logs.start();
        writerLog.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        writerLog.detachAppender(logs);
    }

    private AdminAuditEntry entry(String json) {
        return entry(json, ActorSnapshot.UNKNOWN);
    }

    private AdminAuditEntry entry(String json, ActorSnapshot actor) {
        return new AdminAuditEntry(ACTOR, actor, "support_kb_article.publish", "support_kb_article", "a-42",
                "How to pay",
                json == null ? null : JsonMapper.builder().build().readTree(json),
                AdminAuditEntry.Status.SUCCESS, null, "10.0.0.1", "agent");
    }

    @Test
    void aFailedInsertIsLoggedAndSwallowed() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatCode(() -> writer.write(entry("{\"role\":\"CREATOR\"}"))).doesNotThrowAnyException();

        List<ILoggingEvent> errors = logs.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getFormattedMessage()).contains("support_kb_article.publish").contains("a-42")
                .doesNotContain("CREATOR").doesNotContain("10.0.0.1");
    }

    @Test
    void anUnknownActorStillWritesTheRowWithEmptySnapshot() {
        writer.write(entry(null));

        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        assertThat(params.getValue().getValue("actor_email")).isNull();
        assertThat(params.getValue().getValue("actor_name")).isNull();
        assertThat(params.getValue().getValue("action")).isEqualTo("support_kb_article.publish");
    }

    @Test
    void writesTheEntryWithOriginHttpAndTheSnapshot() {
        writer.write(entry("{\"role\":\"CREATOR\"}", new ActorSnapshot("admin@x.io", "Ada")));

        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        SqlParameterSource p = params.getValue();
        assertThat(p.getValue("actor_user_id")).isEqualTo(ACTOR);
        assertThat(p.getValue("actor_email")).isEqualTo("admin@x.io");
        assertThat(p.getValue("actor_name")).isEqualTo("Ada");
        assertThat(p.getValue("action")).isEqualTo("support_kb_article.publish");
        assertThat(p.getValue("resource_type")).isEqualTo("support_kb_article");
        assertThat(p.getValue("resource_id")).isEqualTo("a-42");
        assertThat(p.getValue("resource_label")).isEqualTo("How to pay");
        assertThat(p.getValue("change_summary")).isEqualTo("{\"role\":\"CREATOR\"}");
        assertThat(p.getValue("status")).isEqualTo("success");
        assertThat(p.getValue("ip")).isEqualTo("10.0.0.1");
        assertThat(p.getValue("user_agent")).isEqualTo("agent");
        assertThat(p.getValue("origin_source")).isEqualTo("http");
    }

    @Test
    void aNullSummaryIsBoundAsNull() {
        writer.write(entry(null));

        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        assertThat(params.getValue().getValue("change_summary")).isNull();
    }
}
