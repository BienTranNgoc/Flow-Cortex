package com.flowcortex.onboarding.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.onboarding.application.OnboardingSampleService.AdminView;
import com.flowcortex.onboarding.application.OnboardingSampleService.SampleWorkflow;
import com.flowcortex.onboarding.domain.SampleTranslations.Translation;
import com.flowcortex.onboarding.domain.StoredSample;
import com.flowcortex.onboarding.infrastructure.OnboardingSampleRepository;
import com.flowcortex.onboarding.infrastructure.OnboardingSampleRepository.Row;
import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.user.api.UserDirectory;
import com.flowcortex.workflow.api.AudienceView;
import com.flowcortex.workflow.api.AudienceView.Audience;
import com.flowcortex.workflow.api.AudienceView.StartKind;
import com.flowcortex.workflow.api.WorkflowAudience;
import com.flowcortex.workflow.api.WorkflowStepView;
import com.flowcortex.workflow.api.WorkflowView;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OnboardingSampleServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-10T09:00:00Z");

    private final UUID adminId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();
    private final StoredSample sample = new StoredSample(workflowId, "Một chiếc áo len", "vi");
    private OnboardingSampleRepository samples;
    private WorkflowAudience audience;
    private UserDirectory users;

    @BeforeEach
    void setUp() {
        samples = mock(OnboardingSampleRepository.class);
        audience = mock(WorkflowAudience.class);
        users = mock(UserDirectory.class);
    }

    private OnboardingSampleService service(boolean switchOn) {
        return new OnboardingSampleService(samples, audience, users, new OnboardingSwitch(switchOn),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private AudienceView view(Audience who) {
        WorkflowStepView step = new WorkflowStepView(UUID.randomUUID(), 1, "step", "echo", "inline", null, null);
        WorkflowView workflow = new WorkflowView(workflowId, UUID.randomUUID(), "Sample", true, Instant.EPOCH,
                Instant.EPOCH, List.of(step), true, "published");
        return new AudienceView(who, workflow, StartKind.TEXT);
    }

    private void stored(StoredSample stored) {
        when(samples.read()).thenReturn(new Row(stored, adminId, NOW));
        when(users.findEmailsByIds(List.of(adminId))).thenReturn(Map.of(adminId, "admin@flowcortex.dev"));
    }

    @Test
    void anUnsetSampleIsViewedAsNothingShown() {
        stored(null);

        AdminView view = service(true).view();

        assertThat(view.workflow()).isNull();
        assertThat(view.shown()).isFalse();
        assertThat(view.translations()).isEmpty();
        assertThat(view.updatedBy()).isEqualTo("admin@flowcortex.dev");
    }

    @Test
    void anEligibleSampleIsViewedWithOneEntryPerEditableLanguage() {
        stored(sample);
        when(audience.describe(workflowId)).thenReturn(view(Audience.ALL_MEMBERS));
        when(samples.translations(sample.sourceHash()))
                .thenReturn(List.of(new Translation(sample.sourceHash(), "en", "A wool sweater")));

        AdminView view = service(true).view();

        assertThat(view.workflow().name()).isEqualTo("Sample");
        assertThat(view.shown()).isTrue();
        assertThat(view.sourceHash()).isEqualTo(sample.sourceHash());
        assertThat(view.translations()).containsExactly(Map.entry("en", "A wool sweater"));
    }

    @Test
    void clearingTheSampleWritesNothingInItsPlace() {
        stored(null);

        service(true).set(adminId, null, null, null);

        verify(samples).write(null, adminId, NOW);
    }

    @Test
    void anIneligibleWorkflowIsRefusedWithItsReasonAndNothingIsWritten() {
        when(audience.describe(workflowId)).thenReturn(view(Audience.OWNER_ONLY));

        assertThatThrownBy(() -> service(true).set(adminId, workflowId, "input", "vi"))
                .isInstanceOfSatisfying(UnprocessableEntityException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("ONBOARDING_SAMPLE_INELIGIBLE");
                    assertThat(e.getErrors().get("workflowId").code())
                            .isEqualTo("onboarding.sample.ineligible.private");
                });
        verify(samples, never()).write(any(), any(), any());
    }

    @Test
    void anEligibleWorkflowIsStored() {
        stored(sample);
        when(audience.describe(workflowId)).thenReturn(view(Audience.ALL_MEMBERS));

        service(true).set(adminId, workflowId, sample.sampleInput(), "vi");

        verify(samples).write(sample, adminId, NOW);
    }

    @Test
    void aTranslationWithNoSampleSetIsNotFound() {
        stored(null);

        assertThatThrownBy(() -> service(true).saveTranslation(adminId, "en", "hash", "text"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void aTranslationForAnOlderSampleIsAConflict() {
        stored(sample);

        assertThatThrownBy(() -> service(true).saveTranslation(adminId, "en", "stale", "text"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void theSourceLanguageCannotBeTranslated() {
        stored(sample);

        assertThatThrownBy(() -> service(true).saveTranslation(adminId, "vi", sample.sourceHash(), "text"))
                .isInstanceOfSatisfying(UnprocessableEntityException.class,
                        e -> assertThat(e.getErrors()).containsOnlyKeys("locale"));
    }

    @Test
    void aBlankTranslationRemovesIt() {
        stored(sample);
        when(audience.describe(workflowId)).thenReturn(view(Audience.ALL_MEMBERS));

        service(true).saveTranslation(adminId, "en", sample.sourceHash(), " ");

        verify(samples).deleteTranslation(sample.sourceHash(), "en");
    }

    @Test
    void aTranslationIsSaved() {
        stored(sample);
        when(audience.describe(workflowId)).thenReturn(view(Audience.ALL_MEMBERS));

        service(true).saveTranslation(adminId, "en", sample.sourceHash(), "A wool sweater");

        verify(samples).upsertTranslation(sample.sourceHash(), "en", "A wool sweater", adminId, NOW);
    }

    @Test
    void nothingIsServedWhileTheSwitchIsOff() {
        assertThat(service(false).servedSample(Locale.ENGLISH)).isEmpty();
    }

    @Test
    void nothingIsServedWhileNoSampleIsSet() {
        stored(null);

        assertThat(service(true).servedSample(Locale.ENGLISH)).isEmpty();
    }

    @Test
    void anIneligibleSampleIsNotServed() {
        stored(sample);
        when(audience.describe(workflowId)).thenReturn(view(Audience.DRAFT));

        assertThat(service(true).servedSample(Locale.ENGLISH)).isEmpty();
    }

    @Test
    void theSampleIsServedInTheMembersLanguage() {
        stored(sample);
        when(audience.describe(workflowId)).thenReturn(view(Audience.ALL_MEMBERS));
        when(samples.translations(sample.sourceHash()))
                .thenReturn(List.of(new Translation(sample.sourceHash(), "en", "A wool sweater")));

        assertThat(service(true).servedSample(Locale.ENGLISH))
                .isEqualTo(Optional.of(new SampleWorkflow(workflowId, "Sample", "A wool sweater")));
        assertThat(service(true).servedSample(Locale.forLanguageTag("vi")))
                .isEqualTo(Optional.of(new SampleWorkflow(workflowId, "Sample", "Một chiếc áo len")));
    }
}
