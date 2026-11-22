package com.flowcortex.app.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.billing.settings.PaymentSettingGroup;
import com.flowcortex.billing.settings.PaymentSettingMeta;
import com.flowcortex.settings.api.SettingChange;
import com.flowcortex.settings.api.SettingChangeEntry;
import com.flowcortex.settings.api.SettingDefinition;
import com.flowcortex.settings.api.SettingDescription;
import com.flowcortex.settings.api.SettingKey;
import com.flowcortex.settings.api.SettingRead;
import com.flowcortex.settings.api.SettingScope;
import com.flowcortex.settings.api.SettingSource;
import com.flowcortex.settings.application.SettingHistoryEntry;
import com.flowcortex.settings.application.SettingsRegistry;
import com.flowcortex.settings.application.SettingsService;
import com.flowcortex.settings.infrastructure.SettingStore;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.PageView;
import com.flowcortex.shared.web.UnprocessableEntityException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The settings store against Postgres: values, history and the stamp commit together, concurrent
 * writers form one chain, and a damaged row degrades without leaking its text. Not transactional:
 * the writers run on their own threads and must commit.
 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class SettingsStoreIT extends AbstractPostgresBootTest {

    private static final SettingKey<Boolean> FLAG = SettingKey.switchKey("test.store.flag");
    private static final SettingKey<Integer> LIMIT = SettingKey.integerKey("test.store.limit");
    private static final String PAY_MODE = "test.store.pay_mode";
    private static final String PAY_LIMIT = "test.store.pay_limit";

    @TestConfiguration
    static class Keys {

        @Bean
        SettingDefinition<Boolean> testStoreFlag() {
            return SettingDefinition.switchKey("test.store.flag", false, () -> null);
        }

        @Bean
        SettingDefinition<Integer> testStoreLimit() {
            return SettingDefinition.integerKey("test.store.limit", 1, 100, () -> 30);
        }

        @Bean
        SettingDefinition<String> testStorePayMode() {
            return SettingDefinition.enumKey("test.store.pay_mode", List.of("a", "b", "c"), "a", () -> "a")
                    .inScope(SettingScope.PAYMENT_OPS);
        }

        @Bean
        SettingDefinition<Integer> testStorePayLimit() {
            return SettingDefinition.integerKey("test.store.pay_limit", 1, 100, () -> 10)
                    .inScope(SettingScope.PAYMENT_OPS);
        }

        @Bean
        PaymentSettingMeta testStorePayModeMeta() {
            return new PaymentSettingMeta(PAY_MODE, PaymentSettingGroup.REGION, false);
        }

        @Bean
        PaymentSettingMeta testStorePayLimitMeta() {
            return new PaymentSettingMeta(PAY_LIMIT, PaymentSettingGroup.REGION, false);
        }
    }

    @Autowired
    private SettingsService settings;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private SettingsRegistry registry;

    @Autowired
    private PlatformTransactionManager transactions;

    private final UUID admin = UUID.randomUUID();

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM app_setting WHERE key LIKE 'test.store.%'");
        jdbc.update("DELETE FROM app_setting_change WHERE key LIKE 'test.store.%'");
    }

    @Test
    void aScopedWriteRecordsItsTrimmedReasonAndAnUnchangedValueRecordsNothing() {
        SettingDescription written = settings.write(PAY_MODE, SettingScope.PAYMENT_OPS,
                JsonNodeFactory.instance.stringNode("b"), admin, "  contract  ");
        settings.write(PAY_MODE, SettingScope.PAYMENT_OPS, JsonNodeFactory.instance.stringNode("b"), admin, "again");
        settings.write(PAY_MODE, SettingScope.PAYMENT_OPS, JsonNodeFactory.instance.stringNode("c"), admin, "   ");

        assertThat(written.effective()).isEqualTo("b");
        assertThat(written.source()).isEqualTo(SettingSource.STORED);
        assertThat(written.options()).containsExactly("a", "b", "c");
        assertThat(jdbc.queryForList("SELECT reason FROM app_setting_change WHERE key = ? ORDER BY changed_at",
                String.class, PAY_MODE)).containsExactly("contract", null);
    }

    @Test
    void aScopedWriteRefusesAReasonOverTheLimitAndAKeyOfAnotherScope() {
        String tooLong = "x".repeat(2001);

        assertThatThrownBy(() -> settings.write(PAY_MODE, SettingScope.PAYMENT_OPS,
                JsonNodeFactory.instance.stringNode("b"), admin, tooLong))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings.write(LIMIT.name(), SettingScope.PAYMENT_OPS,
                JsonNodeFactory.instance.numberNode(5), admin, "x")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> settings.write(PAY_MODE, SettingScope.PAYMENT_OPS,
                JsonNodeFactory.instance.stringNode("B"), admin, "x"))
                .isInstanceOf(UnprocessableEntityException.class);

        assertThat(historyRows(PAY_MODE)).isZero();
        assertThat(historyRows(LIMIT.name())).isZero();
        assertThat(settings.describe(PAY_MODE, SettingScope.PAYMENT_OPS).orElseThrow().source())
                .isEqualTo(SettingSource.CONFIGURED);
        assertThat(settings.describe(LIMIT.name(), SettingScope.PAYMENT_OPS)).isEmpty();
    }

    @Test
    void scopedHistoryCoversEveryKeyOfTheScopeNewestFirstAndNoOther() {
        settings.write(PAY_MODE, SettingScope.PAYMENT_OPS, JsonNodeFactory.instance.stringNode("b"), admin, "one");
        settings.write(PAY_LIMIT, SettingScope.PAYMENT_OPS, JsonNodeFactory.instance.numberNode(20), admin, "two");
        settings.set(LIMIT, 12, admin);
        settings.write(PAY_MODE, SettingScope.PAYMENT_OPS, JsonNodeFactory.instance.stringNode("c"), admin, "three");

        PageView<SettingChangeEntry> all = settings.history(SettingScope.PAYMENT_OPS, Optional.empty(), 0, 10);
        PageView<SettingChangeEntry> second = settings.history(SettingScope.PAYMENT_OPS, Optional.empty(), 1, 2);
        PageView<SettingChangeEntry> one = settings.history(SettingScope.PAYMENT_OPS, Optional.of(PAY_MODE), 0, 10);

        assertThat(all.total()).isEqualTo(3);
        assertThat(all.items()).extracting(SettingChangeEntry::reason).containsExactly("three", "two", "one");
        assertThat(all.items()).extracting(SettingChangeEntry::name)
                .containsExactly(PAY_MODE, PAY_LIMIT, PAY_MODE);
        assertThat(all.items().get(0).previousValue()).isEqualTo("b");
        assertThat(all.items().get(0).newValue()).isEqualTo("c");
        assertThat(second.items()).extracting(SettingChangeEntry::reason).containsExactly("one");
        assertThat(second.total()).isEqualTo(3);
        assertThat(one.total()).isEqualTo(2);
        assertThat(one.items()).extracting(SettingChangeEntry::reason).containsExactly("three", "one");
        assertThatThrownBy(() -> settings.history(SettingScope.PAYMENT_OPS, Optional.of(LIMIT.name()), 0, 10))
                .isInstanceOf(NotFoundException.class);
        assertThat(settings.declaredKeys(SettingScope.PAYMENT_OPS)).contains(PAY_MODE, PAY_LIMIT)
                .doesNotContain(LIMIT.name());
    }

    @Test
    void aWriteIsReadBackByTheNextRead() {
        assertThat(settings.get(LIMIT)).isEqualTo(new SettingRead<>(30, 30, SettingSource.CONFIGURED));

        settings.set(LIMIT, 12, admin);

        assertThat(settings.get(LIMIT)).isEqualTo(new SettingRead<>(12, 12, SettingSource.STORED));
    }

    @Test
    void aChangeRecordsPreviousAndNewWithActor() {
        UUID other = UUID.randomUUID();
        settings.set(LIMIT, 12, admin);
        settings.set(LIMIT, 40, other);

        List<SettingHistoryEntry> history = settings.history(LIMIT.name(), 0, 10).items();

        assertThat(history).hasSize(2);
        assertThat(history.get(0).previousValue()).isEqualTo(12);
        assertThat(history.get(0).newValue()).isEqualTo(40);
        assertThat(history.get(0).changedBy()).isEqualTo(other);
        assertThat(history.get(1).previousValue()).isNull();
        assertThat(history.get(1).newValue()).isEqualTo(12);
        assertThat(history.get(1).changedBy()).isEqualTo(admin);
    }

    @Test
    void writingTheStoredValueAgainRecordsNothing() {
        settings.set(LIMIT, 12, admin);
        Optional<SettingChange> stamp = settings.lastChange(LIMIT);

        settings.set(LIMIT, 12, UUID.randomUUID());

        assertThat(historyRows(LIMIT.name())).isEqualTo(1);
        assertThat(settings.lastChange(LIMIT)).isEqualTo(stamp);
    }

    @Test
    void resetRemovesTheValueAndRecordsOneRow() {
        settings.set(LIMIT, 12, admin);

        settings.reset(LIMIT.name(), admin);

        assertThat(settings.get(LIMIT).source()).isEqualTo(SettingSource.CONFIGURED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_setting WHERE key = ?", Integer.class,
                LIMIT.name())).isZero();
        SettingHistoryEntry newest = settings.history(LIMIT.name(), 0, 10).items().get(0);
        assertThat(newest.previousValue()).isEqualTo(12);
        assertThat(newest.newValue()).isNull();
        assertThat(historyRows(LIMIT.name())).isEqualTo(2);
    }

    @Test
    void resettingAnUnstoredKeyRecordsNothing() {
        settings.reset(LIMIT.name(), admin);

        assertThat(historyRows(LIMIT.name())).isZero();
    }

    @Test
    void tenConcurrentWritesFormOneChain() throws Exception {
        int writers = 10;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            List<Future<?>> writes = new ArrayList<>();
            for (int i = 1; i <= writers; i++) {
                int value = i;
                writes.add(pool.submit(() -> {
                    start.await();
                    settings.set(LIMIT, value, UUID.randomUUID());
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> write : writes) {
                write.get();
            }
        } finally {
            pool.shutdownNow();
        }

        List<SettingHistoryEntry> oldestFirst = new ArrayList<>(settings.history(LIMIT.name(), 0, 100).items());
        Collections.reverse(oldestFirst);
        assertThat(oldestFirst).hasSize(writers);
        assertThat(oldestFirst.get(0).previousValue()).isNull();
        for (int i = 1; i < oldestFirst.size(); i++) {
            assertThat(oldestFirst.get(i).previousValue()).isEqualTo(oldestFirst.get(i - 1).newValue());
        }
        assertThat(settings.get(LIMIT).effective()).isEqualTo(oldestFirst.get(writers - 1).newValue());
    }

    @Test
    void aFailedHistoryInsertLeavesTheValueUnchanged() {
        settings.set(LIMIT, 12, admin);
        SettingStore failing = new SettingStore(jdbc) {
            @Override
            public void insertChange(UUID id, String key, String previous, String next, UUID actor, Instant at,
                    String reason) {
                throw new IllegalStateException("history is down");
            }
        };
        SettingsService broken = new SettingsService(failing, registry, transactions, meters);

        assertThatThrownBy(() -> broken.set(LIMIT, 40, admin)).isInstanceOf(IllegalStateException.class);

        assertThat(settings.get(LIMIT).effective()).isEqualTo(12);
        assertThat(historyRows(LIMIT.name())).isEqualTo(1);
    }

    @Test
    void unparseableStoredTextIsCountedAndNotLogged(CapturedOutput output) {
        String damaged = "garbled-by-hand";
        jdbc.update("INSERT INTO app_setting (key, value, updated_by, updated_at) VALUES (?, ?, NULL, now())",
                LIMIT.name(), damaged);
        double before = degradedCount(LIMIT.name());

        SettingRead<Integer> read = settings.get(LIMIT);

        assertThat(read.source()).isEqualTo(SettingSource.DEGRADED);
        assertThat(read.effective()).isEqualTo(30);
        assertThat(degradedCount(LIMIT.name()) - before).isEqualTo(1.0);
        assertThat(output.getAll()).contains("key=" + LIMIT.name()).doesNotContain(damaged);
    }

    @Test
    void lastChangeIsTheStampOfTheStoredValue() {
        assertThat(settings.lastChange(FLAG)).isEmpty();
        Instant before = Instant.now();

        settings.set(FLAG, true, admin);

        SettingChange stamp = settings.lastChange(FLAG).orElseThrow();
        assertThat(stamp.actor()).isEqualTo(admin);
        assertThat(stamp.at()).isBetween(before.minus(Duration.ofSeconds(5)), Instant.now().plusSeconds(5));

        settings.reset(FLAG.name(), admin);

        assertThat(settings.lastChange(FLAG)).isEmpty();
        assertThat(historyRows(FLAG.name())).isEqualTo(2);
    }

    private int historyRows(String key) {
        return jdbc.queryForObject("SELECT count(*) FROM app_setting_change WHERE key = ?", Integer.class, key);
    }

    private double degradedCount(String key) {
        var counter = meters.find("settings.read.degraded").tag("key", key).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
