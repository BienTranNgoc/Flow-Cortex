package com.flowcortex.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.flowcortex.user.infrastructure.RefreshTokenRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

class AuthRetentionJobTest {

    private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
    private final AuthRetentionJob job = new AuthRetentionJob(jdbc, refreshTokens,
            new AuthRetentionProperties(Duration.ofDays(90)), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void purgeDeletesAuditRowsOlderThanTheRetentionAndDeadRefreshTokens() {
        job.purge();

        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(startsWith("DELETE FROM auth_event "), params.capture());
        assertThat(params.getValue().getValue("cutoff")).isEqualTo(Timestamp.from(NOW.minus(Duration.ofDays(90))));
        verify(refreshTokens).deleteExpiredAndRevokedBefore(NOW);
    }

    @Test
    void purgeDeletesOnlyResetLinksThatWereUsedOrExpiredBeforeTheRun() {
        job.purge();

        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(eq("DELETE FROM email_confirmation_token WHERE purpose = 'PASSWORD_RESET'"
                + " AND (used_at < :now OR expires_at < :now)"), params.capture());
        assertThat(params.getValue().getValue("now")).isEqualTo(Timestamp.from(NOW));
    }
}
