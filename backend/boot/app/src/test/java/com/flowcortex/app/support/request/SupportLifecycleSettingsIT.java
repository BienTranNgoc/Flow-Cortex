package com.flowcortex.app.support.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.settings.api.SettingDefinition;
import com.flowcortex.settings.api.SettingSource;
import com.flowcortex.settings.api.Settings;
import com.flowcortex.settings.application.SettingsRegistry;
import com.flowcortex.support.config.SupportLifecycleSettings;
import com.flowcortex.support.request.SupportRetentionJob;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** The six lifecycle settings: declared with their bounds, refused out of range, and vetoed by the environment. */
@SpringBootTest(properties = {
        "flowcortex.support.enabled=true",
        "flowcortex.support.lifecycle.retention-enabled=false",
        "flowcortex.support.request-retention.interval=PT24H",
        "flowcortex.support.retention.interval=PT24H"})
class SupportLifecycleSettingsIT extends AbstractPostgresBootTest {

    @Autowired
    private SettingsRegistry registry;

    @Autowired
    private Settings settings;

    @Autowired
    private SupportRetentionJob retentionJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanSlate() {
        jdbcTemplate.update("DELETE FROM support_request");
        jdbcTemplate.update("DELETE FROM app_setting WHERE key LIKE 'support.%'");
        jdbcTemplate.update("DELETE FROM app_setting_change WHERE key LIKE 'support.%'");
    }

    @Test
    void theSixKeysAreDeclaredWithTheirBounds() {
        assertThat(registry.all()).filteredOn(definition -> definition.name().matches(
                "support\\.(auto_close|reply_mail|retention)\\..+")).extracting(SettingDefinition::name)
                .containsExactlyInAnyOrder("support.auto_close.enabled", "support.auto_close.resolved_days",
                        "support.reply_mail.enabled", "support.reply_mail.window_minutes",
                        "support.retention.enabled", "support.retention.closed_request_months");
        assertThat(bounds("support.auto_close.resolved_days")).containsExactly(1, 90);
        assertThat(bounds("support.reply_mail.window_minutes")).containsExactly(0, 1440);
        assertThat(bounds("support.retention.closed_request_months")).containsExactly(6, 120);
    }

    @Test
    void aWindowOutsideItsBoundsIsRefusedAndTheWindowStaysAsItWas() {
        assertThatThrownBy(() -> settings.set(SupportLifecycleSettings.REPLY_MAIL_WINDOW_MINUTES, 2000,
                UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);

        assertThat(settings.get(SupportLifecycleSettings.REPLY_MAIL_WINDOW_MINUTES).effective()).isZero();
    }

    @Test
    void aSwitchTheEnvironmentTurnsOffStaysOffWhateverAnAdminStores() {
        settings.set(SupportLifecycleSettings.RETENTION_ENABLED, true, UUID.randomUUID());
        Instant old = Instant.now().atOffset(ZoneOffset.UTC).minusMonths(30).toInstant();
        jdbcTemplate.update("INSERT INTO support_request (id, requester_id, type, subject, status, member_unread, "
                + "last_activity_at, closed_reason, created_at, updated_at) "
                + "VALUES (?, ?, 'credit', 'Không thấy credit', 'closed', false, ?, 'admin', ?, ?)",
                UUID.randomUUID(), UUID.randomUUID(), Timestamp.from(old), Timestamp.from(old), Timestamp.from(old));

        retentionJob.run();

        assertThat(settings.get(SupportLifecycleSettings.RETENTION_ENABLED).source()).isEqualTo(SettingSource.VETOED);
        assertThat(settings.get(SupportLifecycleSettings.RETENTION_ENABLED).effective()).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM support_request", Long.class)).isEqualTo(1L);
    }

    private List<Integer> bounds(String name) {
        SettingDefinition<?> definition = registry.definition(name).orElseThrow();
        return List.of(definition.min(), definition.max());
    }
}
