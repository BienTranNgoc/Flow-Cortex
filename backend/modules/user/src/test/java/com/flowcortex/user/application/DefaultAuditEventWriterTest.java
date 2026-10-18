package com.flowcortex.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class DefaultAuditEventWriterTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"), ZoneOffset.UTC);

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final DefaultAuditEventWriter writer = new DefaultAuditEventWriter(jdbc, FIXED, transactions);

    @Test
    void anInsertFailureIsSwallowedSoAnAuditOutageNeverFailsALogin() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(jdbc.update(anyString(), any(SqlParameterSource.class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatCode(() -> writer.write(AuthEventType.LOGIN_SUCCESS, UUID.randomUUID(), "ip", "ua", Map.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void aFailingCommitIsSwallowedToo() {
        TransactionStatus status = new SimpleTransactionStatus();
        when(transactions.getTransaction(any())).thenReturn(status);
        doThrow(new DataAccessResourceFailureException("commit failed")).when(transactions).commit(status);

        assertThatCode(() -> writer.write(AuthEventType.LOGOUT, UUID.randomUUID(), "ip", "ua", Map.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void theRowIsWrittenInANewTransactionWithTheMetadataAsJson() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        writer.write(AuthEventType.LOGIN_FAILED_BAD_CREDENTIALS, null, "ip", "ua", Map.of("email", "a@b.c"));

        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactions).getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        assertThat(params.getValue().getValue("metadata")).isEqualTo("{\"email\":\"a@b.c\"}");
        assertThat(params.getValue().getValue("type")).isEqualTo("LOGIN_FAILED_BAD_CREDENTIALS");
    }
}
