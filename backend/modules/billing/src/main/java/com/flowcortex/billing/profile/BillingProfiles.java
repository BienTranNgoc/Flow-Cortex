package com.flowcortex.billing.profile;

import com.flowcortex.billing.profile.BillingProfileRules.Invalid;
import com.flowcortex.billing.profile.BillingProfileRules.Outcome;
import com.flowcortex.billing.profile.BillingProfileRules.Valid;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.UnprocessableEntityException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * A member's invoice details. Every call is scoped to the owner it is given. A write takes the member's
 * advisory lock first, so one member's writes run one after the other: the old default is cleared and the new
 * one set without the unique index ever seeing two, and nothing needs a retry.
 */
@Service
public class BillingProfiles {

    /** The most profiles one member keeps; the next create is refused until one is deleted. */
    public static final int MAX_PER_MEMBER = 20;

    static final String LIMIT_REACHED_CODE = "billing_profile_limit_reached";

    private static final String INVALID_CODE = "billing_profile_invalid";
    private static final LocalizedMessage INVALID_DETAIL = new LocalizedMessage("billingProfile.invalid");
    private static final LocalizedMessage LIMIT_REACHED_DETAIL = new LocalizedMessage("billingProfile.limitReached",
            MAX_PER_MEMBER);

    private final BillingProfileRepository repository;
    private final Clock clock;

    BillingProfiles(BillingProfileRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** The member's profiles, the default first and then oldest first. */
    @Transactional(readOnly = true)
    public List<BillingProfile> list(UUID user) {
        return repository.list(user);
    }

    @Transactional(readOnly = true)
    public Optional<BillingProfile> find(UUID user, UUID id) {
        return repository.find(user, id);
    }

    @Transactional(readOnly = true)
    public Optional<BillingProfile> findDefault(UUID user) {
        return repository.findDefault(user);
    }

    /**
     * Stores a profile; the member's first one becomes the default whatever the body says.
     *
     * @throws UnprocessableEntityException when the body breaks the rules of a profile, or the member already
     *         holds {@link #MAX_PER_MEMBER} of them (code {@value #LIMIT_REACHED_CODE}); nothing is stored then
     */
    @Transactional
    public BillingProfile create(UUID user, JsonNode body) {
        Valid valid = requireValid(BillingProfileRules.create(body));
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        repository.lock(user);
        int held = repository.count(user);
        if (held >= MAX_PER_MEMBER) {
            throw new UnprocessableEntityException(LIMIT_REACHED_DETAIL, LIMIT_REACHED_CODE);
        }
        boolean first = held == 0;
        repository.insert(user, id, valid.fields(), now);
        if (first || valid.makeDefault()) {
            makeDefault(user, id, now);
        }
        return repository.find(user, id).orElseThrow();
    }

    /**
     * Replaces the fields present in the patch, checking the rules on the merged profile.
     *
     * @throws BillingProfileNotFoundException when the profile is not the member's
     * @throws UnprocessableEntityException when the merged profile breaks the rules; nothing changes then
     */
    @Transactional
    public BillingProfile update(UUID user, UUID id, JsonNode patch) {
        repository.lock(user);
        BillingProfile stored = repository.find(user, id).orElseThrow(BillingProfileNotFoundException::new);
        Valid valid = requireValid(BillingProfileRules.merge(fieldsOf(stored), patch));
        Instant now = clock.instant();
        repository.update(user, id, valid.fields(), now);
        if (valid.makeDefault() && !stored.isDefault()) {
            makeDefault(user, id, now);
        }
        return repository.find(user, id).orElseThrow();
    }

    /**
     * Makes the profile the default; no change when it already is.
     *
     * @throws BillingProfileNotFoundException when the profile is not the member's
     */
    @Transactional
    public BillingProfile setDefault(UUID user, UUID id) {
        repository.lock(user);
        BillingProfile stored = repository.find(user, id).orElseThrow(BillingProfileNotFoundException::new);
        if (stored.isDefault()) {
            return stored;
        }
        makeDefault(user, id, clock.instant());
        return repository.find(user, id).orElseThrow();
    }

    /**
     * Removes the profile; when it was the default the oldest remaining one becomes the default.
     *
     * @throws BillingProfileNotFoundException when the profile is not the member's
     */
    @Transactional
    public void delete(UUID user, UUID id) {
        repository.lock(user);
        BillingProfile stored = repository.find(user, id).orElseThrow(BillingProfileNotFoundException::new);
        repository.delete(user, id);
        if (stored.isDefault()) {
            repository.oldestId(user).ifPresent(oldest -> repository.markDefault(user, oldest, clock.instant()));
        }
    }

    private void makeDefault(UUID user, UUID id, Instant now) {
        repository.clearDefault(user, now);
        repository.markDefault(user, id, now);
    }

    private static ProfileFields fieldsOf(BillingProfile profile) {
        return new ProfileFields(profile.type(), profile.legalName(), profile.taxCode(),
                profile.personalIdNumber(), profile.address(), profile.email());
    }

    private static Valid requireValid(Outcome outcome) {
        if (outcome instanceof Invalid invalid) {
            Map<String, LocalizedMessage> errors = new LinkedHashMap<>();
            invalid.errors().forEach((field, key) -> errors.put(field, new LocalizedMessage(key)));
            throw new UnprocessableEntityException(INVALID_DETAIL, INVALID_CODE, errors);
        }
        return (Valid) outcome;
    }
}
