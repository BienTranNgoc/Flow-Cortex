package com.flowcortex.user.accountdeletion;

import com.flowcortex.user.accountdeletion.DeletionBlockConditionStore.Condition;
import com.flowcortex.user.accountdeletion.DeletionBlockConditionStore.Defaults;
import com.flowcortex.user.api.AccountDeletionBlocker;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/** Asks every enabled blocker whether an account may be deleted and words the answer in the caller's language. */
@Component
class DeletionEligibility {

    private static final Logger LOG = LoggerFactory.getLogger(DeletionEligibility.class);

    /** One matching condition, its message already in the caller's language. */
    record Reason(String key, String message) {
    }

    record Eligibility(boolean eligible, List<Reason> reasons) {
    }

    private final DeletionBlockerRegistry registry;
    private final DeletionBlockConditionStore store;

    DeletionEligibility(DeletionBlockerRegistry registry, DeletionBlockConditionStore store) {
        this.registry = registry;
        this.store = store;
    }

    /**
     * Seeds the rows of conditions that have none, then collects every enabled matching condition.
     *
     * @throws EligibilityUnavailableException when any enabled condition's read fails; no partial list is returned
     */
    Eligibility evaluate(UUID userId) {
        store.insertMissing(registry.all().stream()
                .map(blocker -> new Defaults(blocker.key(), blocker.defaultSortOrder(),
                        blocker.defaultMessageVi(), blocker.defaultMessageEn()))
                .toList());
        Map<String, Condition> rows = store.findAll().stream()
                .collect(Collectors.toMap(Condition::key, Function.identity()));
        boolean vietnamese = "vi".equals(LocaleContextHolder.getLocale().getLanguage());

        List<Condition> matches = new ArrayList<>();
        for (AccountDeletionBlocker blocker : registry.all()) {
            Condition row = rows.get(blocker.key());
            if (row != null && row.enabled() && blocks(blocker, userId)) {
                matches.add(row);
            }
        }
        List<Reason> reasons = matches.stream()
                .sorted(Comparator.comparingInt(Condition::sortOrder).thenComparing(Condition::key))
                .map(row -> new Reason(row.key(), vietnamese ? row.messageVi() : row.messageEn()))
                .toList();
        return new Eligibility(reasons.isEmpty(), reasons);
    }

    private static boolean blocks(AccountDeletionBlocker blocker, UUID userId) {
        try {
            return blocker.blocks(userId);
        } catch (RuntimeException e) {
            LOG.error("account.deletion.eligibility outcome=unavailable key={}", blocker.key(), e);
            throw new EligibilityUnavailableException(blocker.key(), e);
        }
    }
}
