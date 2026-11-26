package com.flowcortex.user.accountdeletion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.user.accountdeletion.DeletionBlockConditionStore.Condition;
import com.flowcortex.user.accountdeletion.DeletionBlockConditionStore.Defaults;
import com.flowcortex.user.accountdeletion.DeletionEligibility.Eligibility;
import com.flowcortex.user.accountdeletion.DeletionEligibility.Reason;
import com.flowcortex.user.api.AccountDeletionBlocker;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

class DeletionEligibilityTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final FakeStore store = new FakeStore();

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void everyMatchIsReturnedInSortOrderThenKey() {
        FakeBlocker late = new FakeBlocker("a.late", 5, true);
        FakeBlocker second = new FakeBlocker("c.second", 1, true);
        FakeBlocker first = new FakeBlocker("b.first", 1, true);
        store.put(late.key, true, 5, "vi a", "en a");
        store.put(second.key, true, 1, "vi c", "en c");
        store.put(first.key, true, 1, "vi b", "en b");

        Eligibility result = evaluator(late, second, first).evaluate(USER);

        assertThat(result.eligible()).isFalse();
        assertThat(result.reasons()).extracting(Reason::key).containsExactly("b.first", "c.second", "a.late");
    }

    @Test
    void aDisabledConditionIsNotCalled() {
        FakeBlocker disabled = new FakeBlocker("workflow.active_run", 0, true);
        FakeBlocker enabled = new FakeBlocker("support.open_request", 1, false);
        store.put(disabled.key, false, 0, "vi", "en");
        store.put(enabled.key, true, 1, "vi", "en");

        Eligibility result = evaluator(disabled, enabled).evaluate(USER);

        assertThat(disabled.calls).isZero();
        assertThat(enabled.calls).isEqualTo(1);
        assertThat(result.eligible()).isTrue();
    }

    @Test
    void vietnameseWhenTheLocaleIsVi() {
        FakeBlocker blocker = new FakeBlocker("workflow.active_run", 0, true);
        store.put(blocker.key, true, 0, "Tin tieng Viet", "English text");

        LocaleContextHolder.setLocale(Locale.forLanguageTag("vi-VN"));

        assertThat(evaluator(blocker).evaluate(USER).reasons()).extracting(Reason::message)
                .containsExactly("Tin tieng Viet");
    }

    @Test
    void englishOtherwise() {
        FakeBlocker blocker = new FakeBlocker("workflow.active_run", 0, true);
        store.put(blocker.key, true, 0, "Tin tieng Viet", "English text");

        LocaleContextHolder.setLocale(Locale.forLanguageTag("fr"));

        assertThat(evaluator(blocker).evaluate(USER).reasons()).extracting(Reason::message)
                .containsExactly("English text");
    }

    @Test
    void noMatchIsEligible() {
        Eligibility result = evaluator(new FakeBlocker("workflow.active_run", 0, false)).evaluate(USER);

        assertThat(result.eligible()).isTrue();
        assertThat(result.reasons()).isEmpty();
    }

    @Test
    void aMissingRowUsesDefaultsAndIsInserted() {
        FakeBlocker blocker = new FakeBlocker("workflow.active_run", 3, true);
        LocaleContextHolder.setLocale(Locale.forLanguageTag("vi"));

        Eligibility result = evaluator(blocker).evaluate(USER);

        assertThat(result.reasons()).containsExactly(new Reason("workflow.active_run", "vi default"));
        assertThat(store.rows).containsKey("workflow.active_run");
        assertThat(store.rows.get("workflow.active_run").sortOrder()).isEqualTo(3);
        assertThat(store.rows.get("workflow.active_run").enabled()).isTrue();
    }

    @Test
    void aThrowingBlockerMakesItUnavailable() {
        FakeBlocker matching = new FakeBlocker("support.open_request", 1, true);
        FakeBlocker failing = new FakeBlocker("workflow.active_run", 0, false);
        failing.failure = new IllegalStateException("db down");

        assertThatThrownBy(() -> evaluator(matching, failing).evaluate(USER))
                .isInstanceOf(EligibilityUnavailableException.class)
                .hasMessageContaining("workflow.active_run");
    }

    @Test
    void disablingTheFailingConditionMakesItAnswer() {
        FakeBlocker failing = new FakeBlocker("workflow.active_run", 0, false);
        failing.failure = new IllegalStateException("db down");
        assertThatThrownBy(() -> evaluator(failing).evaluate(USER)).isInstanceOf(EligibilityUnavailableException.class);

        store.put(failing.key, false, 0, "vi", "en");

        assertThat(evaluator(failing).evaluate(USER).eligible()).isTrue();
        assertThat(failing.calls).isEqualTo(1);
    }

    @Test
    void rowsOfUnregisteredKeysAreIgnored() {
        FakeBlocker registered = new FakeBlocker("workflow.active_run", 0, false);
        store.put("retired.condition", true, 0, "vi", "en");

        Eligibility result = evaluator(registered).evaluate(USER);

        assertThat(result.eligible()).isTrue();
        assertThat(result.reasons()).isEmpty();
    }

    private DeletionEligibility evaluator(FakeBlocker... blockers) {
        return new DeletionEligibility(new DeletionBlockerRegistry(List.of(blockers)), store);
    }

    private static final class FakeBlocker implements AccountDeletionBlocker {

        final String key;
        final int order;
        final boolean matches;
        RuntimeException failure;
        int calls;

        FakeBlocker(String key, int order, boolean matches) {
            this.key = key;
            this.order = order;
            this.matches = matches;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public int defaultSortOrder() {
            return order;
        }

        @Override
        public String defaultMessageVi() {
            return "vi default";
        }

        @Override
        public String defaultMessageEn() {
            return "en default";
        }

        @Override
        public boolean blocks(UUID userId) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return matches;
        }
    }

    private static final class FakeStore extends DeletionBlockConditionStore {

        final Map<String, Condition> rows = new LinkedHashMap<>();

        FakeStore() {
            super(null);
        }

        void put(String key, boolean enabled, int sortOrder, String vi, String en) {
            rows.put(key, new Condition(key, enabled, sortOrder, vi, en, null, Instant.EPOCH));
        }

        @Override
        void insertMissing(List<Defaults> defaults) {
            for (Defaults row : defaults) {
                rows.computeIfAbsent(row.key(), key -> new Condition(
                        key, true, row.sortOrder(), row.messageVi(), row.messageEn(), null, Instant.EPOCH));
            }
        }

        @Override
        List<Condition> findAll() {
            return new ArrayList<>(rows.values());
        }
    }
}
