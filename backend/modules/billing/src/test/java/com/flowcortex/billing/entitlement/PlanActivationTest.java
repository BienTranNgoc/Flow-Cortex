package com.flowcortex.billing.entitlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowcortex.billing.entitlement.EntitlementRepository.DuePending;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class PlanActivationTest {

    private static final Instant NOW = Instant.parse("2027-01-10T03:05:00Z");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID NEXT_PAYMENT = UUID.fromString("00000000-0000-4000-8000-0000000000b1");

    private final EntitlementRepository repository = mock(EntitlementRepository.class);
    private final EntitlementTelemetry telemetry = mock(EntitlementTelemetry.class);

    private PlanActivation job(boolean enabled) {
        return new PlanActivation(repository, new PlanActivator(repository), telemetry,
                new EntitlementActivationProperties(enabled, null, 100), Clock.fixed(NOW, ZoneOffset.UTC),
                mock(PlatformTransactionManager.class));
    }

    @Test
    void aSwitchedOffJobActivatesNothingAndReadsNothing() {
        assertThat(job(false).runAt(NOW)).isZero();

        verifyNoInteractions(repository);
    }

    @Test
    void aBatchSizeOutsideOneToAThousandIsRefusedAtStartup() {
        assertThatThrownBy(() -> new EntitlementActivationProperties(true, null, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("batch-size");
        assertThatThrownBy(() -> new EntitlementActivationProperties(true, null, 1001))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new EntitlementActivationProperties(null, null, null)).satisfies(defaults -> {
            assertThat(defaults.enabled()).isTrue();
            assertThat(defaults.batchSize()).isEqualTo(100);
            assertThat(defaults.interval()).hasMinutes(5);
        });
    }

    @Test
    void aHoldingWhoseNextPlanChangedBetweenSelectionAndWriteChangesNothingAndLeavesNoHistory() {
        DuePending due = new DuePending(NEXT_PAYMENT, "advanced", "monthly", Instant.parse("2027-01-10T03:00:00Z"),
                Instant.parse("2027-02-10T03:00:00Z"), "basic", "active");
        when(repository.findDuePending(MEMBER, NOW)).thenReturn(Optional.of(due));
        when(repository.activate(MEMBER, due, NOW)).thenReturn(0);

        assertThat(new PlanActivator(repository).activateDue(MEMBER, NOW)).isEmpty();

        verify(repository, never()).appendEvent(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void anActivationWritesOneHistoryEntryNamingTheNewTier() {
        DuePending due = new DuePending(NEXT_PAYMENT, "advanced", "monthly", Instant.parse("2027-01-10T03:00:00Z"),
                Instant.parse("2027-02-10T03:00:00Z"), "basic", "active");
        when(repository.findDuePending(MEMBER, NOW)).thenReturn(Optional.of(due));
        when(repository.activate(MEMBER, due, NOW)).thenReturn(1);

        assertThat(new PlanActivator(repository).activateDue(MEMBER, NOW)).contains("advanced");

        verify(repository).appendEvent(MEMBER, NEXT_PAYMENT, "basic", "advanced", "active", "active",
                "renewal_plan_activated:advanced", NOW);
    }
}
