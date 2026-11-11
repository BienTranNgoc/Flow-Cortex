package com.flowcortex.onboarding.application;

import com.flowcortex.onboarding.application.SampleAuditLog.Outcome;
import com.flowcortex.onboarding.domain.IneligibleReason;
import com.flowcortex.onboarding.domain.SampleEligibility;
import com.flowcortex.onboarding.domain.SampleOffer;
import com.flowcortex.onboarding.domain.SampleTranslations;
import com.flowcortex.onboarding.domain.StoredSample;
import com.flowcortex.onboarding.domain.TranslationEdit;
import com.flowcortex.onboarding.infrastructure.OnboardingSampleRepository;
import com.flowcortex.onboarding.infrastructure.OnboardingSampleRepository.Row;
import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.user.api.UserDirectory;
import com.flowcortex.workflow.api.AudienceView;
import com.flowcortex.workflow.api.WorkflowAudience;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The onboarding sample: which workflow is offered to new members, the input that fills its start
 * form, and that input's per-language translations. Eligibility is judged on every read, so a
 * workflow that stops being servable stops being offered without any write.
 */
@Service
public class OnboardingSampleService {

    private static final String INELIGIBLE_CODE = "ONBOARDING_SAMPLE_INELIGIBLE";
    private static final String LOCALE_CODE = "ONBOARDING_SAMPLE_LOCALE";

    private final OnboardingSampleRepository samples;
    private final WorkflowAudience audience;
    private final UserDirectory users;
    private final OnboardingSwitch onboardingSwitch;
    private final Clock clock;

    OnboardingSampleService(OnboardingSampleRepository samples, WorkflowAudience audience, UserDirectory users,
            OnboardingSwitch onboardingSwitch, Clock clock) {
        this.samples = samples;
        this.audience = audience;
        this.users = users;
        this.onboardingSwitch = onboardingSwitch;
        this.clock = clock;
    }

    /** The workflow the admin chose; {@code name} is null when the workflow no longer exists. */
    public record WorkflowRef(UUID id, String name) {
    }

    /**
     * The admin's read view. {@code translations} has one entry per editable language, null while
     * that language has none; {@code updatedBy} is the admin's email, null when unset or the account is gone.
     */
    public record AdminView(WorkflowRef workflow, boolean shown, String reason, String sampleInput,
            String sourceLocale, String sourceHash, Map<String, String> translations, String updatedBy,
            Instant updatedAt) {
    }

    /** What a member is offered: the workflow and the input in the member's language. */
    public record SampleWorkflow(UUID id, String name, String input) {
    }

    @Transactional(readOnly = true)
    public AdminView view() {
        Row row = samples.read();
        String updatedBy = row.updatedBy() == null ? null : users.findEmailsByIds(List.of(row.updatedBy()))
                .get(row.updatedBy());
        StoredSample sample = row.sample();
        if (sample == null) {
            return new AdminView(null, false, null, null, null, null, Map.of(), updatedBy, row.updatedAt());
        }
        AudienceView described = audience.describe(sample.workflowId());
        SampleOffer offer = SampleOffer.of(SampleEligibility.judge(described), onboardingSwitch.enabled());
        String hash = sample.sourceHash();
        List<SampleTranslations.Translation> stored = samples.translations(hash);
        Map<String, String> translations = new LinkedHashMap<>();
        for (String locale : SampleTranslations.editableLocales(sample.sourceLocale())) {
            translations.put(locale, stored.stream().filter(t -> t.locale().equals(locale))
                    .map(SampleTranslations.Translation::input).findFirst().orElse(null));
        }
        WorkflowRef workflow = described.workflow() == null ? null
                : new WorkflowRef(described.workflow().id(), described.workflow().name());
        return new AdminView(workflow, offer.shown(), offer.reason(), sample.sampleInput(), sample.sourceLocale(),
                hash, translations, updatedBy, row.updatedAt());
    }

    /** Stores the sample, or clears it when {@code workflowId} is null; an ineligible workflow changes nothing. */
    @Transactional
    public AdminView set(UUID adminId, UUID workflowId, String sampleInput, String sourceLocale) {
        if (workflowId == null) {
            samples.write(null, adminId, clock.instant());
            SampleAuditLog.sample(Outcome.CLEARED, adminId, null, null);
            return view();
        }
        Optional<IneligibleReason> verdict = SampleEligibility.judge(audience.describe(workflowId));
        if (verdict.isPresent()) {
            SampleAuditLog.sample(Outcome.REFUSED, adminId, workflowId, verdict.get().code());
            throw new UnprocessableEntityException(new LocalizedMessage("onboarding.sample.ineligible"),
                    INELIGIBLE_CODE,
                    Map.of("workflowId", ineligibleMessage(verdict.get())));
        }
        samples.write(new StoredSample(workflowId, sampleInput, sourceLocale), adminId, clock.instant());
        SampleAuditLog.sample(Outcome.SAVED, adminId, workflowId, null);
        return view();
    }

    /** Saves the translation for the sample the admin was looking at; a blank input removes it. */
    @Transactional
    public AdminView saveTranslation(UUID adminId, String locale, String sourceHash, String input) {
        StoredSample stored = samples.read().sample();
        switch (TranslationEdit.check(stored, locale, sourceHash, input)) {
            case NOT_SET -> {
                SampleAuditLog.translation(Outcome.REFUSED, adminId, locale, "not_set");
                throw new NotFoundException(new LocalizedMessage("onboarding.sample.notSet"));
            }
            case STALE -> {
                SampleAuditLog.translation(Outcome.REFUSED, adminId, locale, "changed");
                throw new ConflictException(new LocalizedMessage("onboarding.sample.changed"));
            }
            case LOCALE_NOT_EDITABLE -> {
                SampleAuditLog.translation(Outcome.REFUSED, adminId, locale, "locale_not_editable");
                LocalizedMessage message = new LocalizedMessage("onboarding.sample.localeNotEditable");
                throw new UnprocessableEntityException(message, LOCALE_CODE, Map.of("locale", message));
            }
            case CLEAR -> {
                samples.deleteTranslation(sourceHash, locale);
                SampleAuditLog.translation(Outcome.CLEARED, adminId, locale, null);
            }
            case SAVE -> {
                samples.upsertTranslation(sourceHash, locale, input, adminId, clock.instant());
                SampleAuditLog.translation(Outcome.SAVED, adminId, locale, null);
            }
        }
        return view();
    }

    /** The sample as the member's language serves it; empty while the switch is off or nothing eligible is set. */
    @Transactional(readOnly = true)
    public Optional<SampleWorkflow> servedSample(Locale locale) {
        if (!onboardingSwitch.enabled()) {
            return Optional.empty();
        }
        StoredSample sample = samples.read().sample();
        if (sample == null) {
            return Optional.empty();
        }
        AudienceView described = audience.describe(sample.workflowId());
        if (SampleEligibility.judge(described).isPresent()) {
            return Optional.empty();
        }
        String input = SampleTranslations.servedInput(sample, samples.translations(sample.sourceHash()),
                locale.getLanguage());
        return Optional.of(new SampleWorkflow(sample.workflowId(), described.workflow().name(), input));
    }

    /** Each reason is a literal so the bundle parity test sees every key. */
    private static LocalizedMessage ineligibleMessage(IneligibleReason reason) {
        return switch (reason) {
            case NOT_FOUND -> new LocalizedMessage("onboarding.sample.ineligible.not_found");
            case DELETED -> new LocalizedMessage("onboarding.sample.ineligible.deleted");
            case UNPUBLISHED -> new LocalizedMessage("onboarding.sample.ineligible.unpublished");
            case PRIVATE -> new LocalizedMessage("onboarding.sample.ineligible.private");
            case NO_STEPS -> new LocalizedMessage("onboarding.sample.ineligible.no_steps");
            case UNAVAILABLE -> new LocalizedMessage("onboarding.sample.ineligible.unavailable");
            case NEEDS_UPLOAD -> new LocalizedMessage("onboarding.sample.ineligible.needs_upload");
            case NEEDS_LINK -> new LocalizedMessage("onboarding.sample.ineligible.needs_link");
            case MANUAL_STEP -> new LocalizedMessage("onboarding.sample.ineligible.manual_step");
        };
    }
}
