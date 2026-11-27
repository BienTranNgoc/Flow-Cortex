package com.flowcortex.support.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowcortex.settings.api.SettingRead;
import com.flowcortex.settings.api.SettingSource;
import com.flowcortex.settings.api.Settings;
import com.flowcortex.support.config.SupportSlaSettings;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The SLA table: status x time since last activity x thresholds, strictly greater-than at each cut. */
class SlaLevelTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final SlaThresholds DEFAULTS = new SlaThresholds(24, 20, 3, 2);

    private static Instant ago(Duration age) {
        return NOW.minus(age);
    }

    @Test
    void aNewRequestPastTheWarningIsDueSoon() {
        assertThat(SlaLevel.of(RequestStatus.NEW, ago(Duration.ofHours(21)), NOW, DEFAULTS))
                .isEqualTo(SlaLevel.DUE_SOON);
    }

    @Test
    void exactlyAtTheLimitIsNotOverdue() {
        assertThat(SlaLevel.of(RequestStatus.NEW, ago(Duration.ofHours(24)), NOW, DEFAULTS))
                .isEqualTo(SlaLevel.DUE_SOON);
    }

    @Test
    void exactlyAtTheWarningIsOk() {
        assertThat(SlaLevel.of(RequestStatus.NEW, ago(Duration.ofHours(20)), NOW, DEFAULTS)).isEqualTo(SlaLevel.OK);
    }

    @Test
    void aNewRequestPastTheLimitIsOverdue() {
        assertThat(SlaLevel.of(RequestStatus.NEW, ago(Duration.ofHours(24).plusSeconds(1)), NOW, DEFAULTS))
                .isEqualTo(SlaLevel.OVERDUE);
    }

    @Test
    void inProgressUsesDays() {
        assertThat(SlaLevel.of(RequestStatus.IN_PROGRESS, ago(Duration.ofDays(4)), NOW, DEFAULTS))
                .isEqualTo(SlaLevel.OVERDUE);
        assertThat(SlaLevel.of(RequestStatus.IN_PROGRESS, ago(Duration.ofDays(2).plusHours(1)), NOW, DEFAULTS))
                .isEqualTo(SlaLevel.DUE_SOON);
        assertThat(SlaLevel.of(RequestStatus.IN_PROGRESS, ago(Duration.ofDays(2)), NOW, DEFAULTS))
                .isEqualTo(SlaLevel.OK);
        assertThat(SlaLevel.of(RequestStatus.IN_PROGRESS, ago(Duration.ofDays(3)), NOW, DEFAULTS))
                .isEqualTo(SlaLevel.DUE_SOON);
    }

    @Test
    void waitingResolvedAndClosedArePaused() {
        Instant old = ago(Duration.ofDays(30));
        assertThat(SlaLevel.of(RequestStatus.WAITING_MEMBER, old, NOW, DEFAULTS)).isEqualTo(SlaLevel.PAUSED);
        assertThat(SlaLevel.of(RequestStatus.RESOLVED, old, NOW, DEFAULTS)).isEqualTo(SlaLevel.PAUSED);
        assertThat(SlaLevel.of(RequestStatus.CLOSED, old, NOW, DEFAULTS)).isEqualTo(SlaLevel.PAUSED);
    }

    @Test
    void aWarningAboveItsLimitNeverReadsDueSoon() {
        SlaThresholds misordered = new SlaThresholds(24, 30, 3, 5);
        assertThat(SlaLevel.of(RequestStatus.NEW, ago(Duration.ofHours(25)), NOW, misordered))
                .isEqualTo(SlaLevel.OVERDUE);
        assertThat(SlaLevel.of(RequestStatus.NEW, ago(Duration.ofHours(23)), NOW, misordered)).isEqualTo(SlaLevel.OK);
        assertThat(SlaLevel.of(RequestStatus.IN_PROGRESS, ago(Duration.ofDays(4)), NOW, misordered))
                .isEqualTo(SlaLevel.OVERDUE);
        assertThat(SlaLevel.of(RequestStatus.IN_PROGRESS, ago(Duration.ofDays(2)), NOW, misordered))
                .isEqualTo(SlaLevel.OK);
    }

    @Test
    void aWarningEqualToItsLimitNeverReadsDueSoon() {
        SlaThresholds equal = new SlaThresholds(24, 24, 3, 3);
        assertThat(SlaLevel.of(RequestStatus.NEW, ago(Duration.ofHours(24)), NOW, equal)).isEqualTo(SlaLevel.OK);
        assertThat(SlaLevel.of(RequestStatus.NEW, ago(Duration.ofHours(25)), NOW, equal)).isEqualTo(SlaLevel.OVERDUE);
        assertThat(SlaLevel.of(RequestStatus.IN_PROGRESS, ago(Duration.ofDays(3)), NOW, equal))
                .isEqualTo(SlaLevel.OK);
    }

    @Test
    void aRequestActiveAfterNowIsOk() {
        assertThat(SlaLevel.of(RequestStatus.NEW, NOW.plusSeconds(5), NOW, DEFAULTS)).isEqualTo(SlaLevel.OK);
    }

    @Test
    void onlyDueSoonAndOverdueAreAtRisk() {
        assertThat(SlaLevel.OK.atRisk()).isFalse();
        assertThat(SlaLevel.PAUSED.atRisk()).isFalse();
        assertThat(SlaLevel.DUE_SOON.atRisk()).isTrue();
        assertThat(SlaLevel.OVERDUE.atRisk()).isTrue();
    }

    @Test
    void theWarningCutsNeverExceedTheirLimits() {
        SlaThresholds misordered = new SlaThresholds(24, 30, 3, 5);
        assertThat(misordered.newWarnCut()).isEqualTo(Duration.ofHours(24));
        assertThat(misordered.inProgressWarnCut()).isEqualTo(Duration.ofDays(3));
        assertThat(DEFAULTS.newWarnCut()).isEqualTo(Duration.ofHours(20));
        assertThat(DEFAULTS.inProgressWarnCut()).isEqualTo(Duration.ofDays(2));
    }

    @Test
    void readTakesTheEffectiveValueOfEachSetting() {
        Settings settings = mock(Settings.class);
        when(settings.get(SupportSlaSettings.NEW_HOURS)).thenReturn(read(8));
        when(settings.get(SupportSlaSettings.NEW_WARN_HOURS)).thenReturn(read(6));
        when(settings.get(SupportSlaSettings.IN_PROGRESS_DAYS)).thenReturn(read(5));
        when(settings.get(SupportSlaSettings.IN_PROGRESS_WARN_DAYS)).thenReturn(read(4));

        assertThat(SlaThresholds.read(settings)).isEqualTo(new SlaThresholds(8, 6, 5, 4));
    }

    private static SettingRead<Integer> read(int value) {
        return new SettingRead<>(value, value, SettingSource.STORED);
    }
}
