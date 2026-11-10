package com.flowcortex.onboarding.application;

import com.flowcortex.onboarding.domain.BuilderState;
import com.flowcortex.onboarding.domain.InvalidOnboardingRequest;
import com.flowcortex.onboarding.domain.OnboardingOpen;
import com.flowcortex.onboarding.domain.OnboardingTracks;
import com.flowcortex.onboarding.domain.ProgressPatch;
import com.flowcortex.onboarding.domain.ProgressRequestParser;
import com.flowcortex.onboarding.domain.QuizCompletion;
import com.flowcortex.onboarding.domain.QuizRequest;
import com.flowcortex.onboarding.domain.QuizRequestParser;
import com.flowcortex.onboarding.domain.Recommendation;
import com.flowcortex.onboarding.domain.StoredAnswers;
import com.flowcortex.onboarding.infrastructure.OnboardingRecord;
import com.flowcortex.onboarding.infrastructure.OnboardingRepository;
import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.workflow.api.RunHistory;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A member's onboarding: answers, journey progress and builder state. Each write runs in one
 * transaction that first ensures the member's row, so first writes never race; a refused body
 * is rejected before anything is stored.
 */
@Service
public class OnboardingService {

    private static final String INVALID_CODE = "ONBOARDING_INVALID";

    private final OnboardingRepository repository;
    private final RunHistory runHistory;
    private final OnboardingSwitch onboardingSwitch;
    private final Clock clock;

    OnboardingService(OnboardingRepository repository, RunHistory runHistory, OnboardingSwitch onboardingSwitch,
            Clock clock) {
        this.repository = repository;
        this.runHistory = runHistory;
        this.onboardingSwitch = onboardingSwitch;
        this.clock = clock;
    }

    /** The member's stored onboarding plus the first-run fact and the open rule's verdict. */
    public record Status(OnboardingRecord record, boolean firstRunDone, boolean open, String entry) {

        public boolean completed() {
            return record.completedAt() != null;
        }
    }

    public record QuizSaved(boolean completed, Instant completedAt, boolean skipped) {
    }

    public record ProgressSaved(ObjectNode progress, Instant completedAt) {
    }

    public record Result(boolean completed, Recommendation recommendation, String aiLevel) {
    }

    public record Builder(ObjectNode state, Instant completedAt) {
    }

    @Transactional(readOnly = true)
    public Status status(UUID userId) {
        OnboardingRecord record = repository.find(userId).orElseGet(OnboardingRecord::empty);
        boolean firstRunDone = runHistory.hasCompletedRun(userId);
        boolean open = OnboardingOpen.isOpen(record.progress(), firstRunDone, onboardingSwitch.enabled());
        return new Status(record, firstRunDone, open, OnboardingOpen.entry(record.progress(), open));
    }

    @Transactional
    public QuizSaved submitQuiz(UUID userId, JsonNode body) {
        QuizRequest request = parsed(() -> QuizRequestParser.parse(body));
        Instant now = clock.instant();
        repository.ensureRow(userId, now);
        OnboardingRecord stored = lock(userId);
        repository.updateAnswers(userId, QuizCompletion.answersToWrite(request), now);
        Instant completedAt = stored.completedAt();
        if (QuizCompletion.completesNow(request, stored.answers(), completedAt)) {
            completedAt = repository.markCompleted(userId, now);
        }
        return new QuizSaved(completedAt != null, completedAt, request.skipped());
    }

    /**
     * Writes journey progress. A closed flow or a switched-off journey is a 409, except a finishing
     * write that lost a race against another finish, which reads back the stored progress.
     */
    @Transactional
    public ProgressSaved saveProgress(UUID userId, JsonNode body) {
        ProgressPatch patch = parsed(() -> ProgressRequestParser.parse(body));
        Instant now = clock.instant();
        repository.ensureRow(userId, now);
        boolean switchOn = onboardingSwitch.enabled();
        boolean firstRunDone = runHistory.hasCompletedRun(userId);
        Optional<ObjectNode> written = repository.writeProgress(userId, patch, switchOn, firstRunDone, now);
        ObjectNode progress = written.orElseGet(() -> alreadyFinished(userId, patch, switchOn));
        Instant completedAt = patch.finishing() ? repository.markCompleted(userId, now) : null;
        return new ProgressSaved(progress, completedAt);
    }

    @Transactional(readOnly = true)
    public Result result(UUID userId) {
        OnboardingRecord record = repository.find(userId).orElseGet(OnboardingRecord::empty);
        StoredAnswers answers = record.answers();
        return new Result(record.completedAt() != null,
                OnboardingTracks.of(answers.industries(), answers.usageType()), answers.aiLevel());
    }

    /** Clears the answers, the completion and the progress; builder state stays. */
    @Transactional
    public void reset(UUID userId) {
        repository.reset(userId, clock.instant());
    }

    @Transactional
    public Builder saveBuilderProgress(UUID userId, JsonNode body) {
        JsonNode incoming = body == null ? null : body.get("state");
        Instant now = clock.instant();
        repository.ensureRow(userId, now);
        OnboardingRecord stored = lock(userId);
        ObjectNode merged = parsed(() -> BuilderState.merge(stored.builderState(), incoming));
        repository.writeBuilderState(userId, merged, now);
        return new Builder(merged, stored.builderCompletedAt());
    }

    @Transactional
    public Builder completeBuilder(UUID userId, JsonNode body) {
        Instant now = clock.instant();
        repository.ensureRow(userId, now);
        OnboardingRecord stored = lock(userId);
        ObjectNode state = parsed(() -> BuilderState.withReason(stored.builderState(), body));
        OnboardingRecord completed = repository.completeBuilder(userId, state, now);
        return new Builder(completed.builderState(), completed.builderCompletedAt());
    }

    private ObjectNode alreadyFinished(UUID userId, ProgressPatch patch, boolean switchOn) {
        if (switchOn && patch.finishing()) {
            ObjectNode stored = repository.readProgress(userId);
            JsonNode version = stored.get("v");
            JsonNode finishedAt = stored.get("finishedAt");
            if (version != null && version.asInt() == ProgressPatch.VERSION && finishedAt != null
                    && !finishedAt.isNull()) {
                return stored;
            }
        }
        throw new ConflictException(new LocalizedMessage("onboarding.flow.closed"));
    }

    private OnboardingRecord lock(UUID userId) {
        return repository.findForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("Onboarding row missing for " + userId));
    }

    private static <T> T parsed(Supplier<T> parse) {
        try {
            return parse.get();
        } catch (InvalidOnboardingRequest refused) {
            Map<String, LocalizedMessage> errors = new LinkedHashMap<>();
            refused.errors().forEach((field, key) -> errors.put(field, OnboardingMessages.of(key)));
            throw new UnprocessableEntityException(new LocalizedMessage("onboarding.request.invalid"), INVALID_CODE,
                    errors);
        }
    }
}
