package com.flowcortex.app.trash;

import com.flowcortex.trash.api.PurgeOutcome;
import com.flowcortex.trash.api.RestoreOutcome;
import com.flowcortex.trash.api.TrashItemRef;
import com.flowcortex.trash.api.TrashProvider;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.HierarchicalMessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The Trash's two test providers, {@code demo} (rank 10, reminds) and {@code demo_late} (rank 20, no reminder), over
 * in-memory rows, and the message codes and settable clock the Trash tests share. Test-only codes sit in a parent
 * message source, never in the production bundles.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TrashTestProviders {

    public static final String LOCATION = "trash.location.demo";
    public static final String NAME_TAKEN = "demo.nameTaken";
    public static final String IN_USE = "demo.inUse";

    @Bean
    CallLog trashCallLog() {
        return new CallLog();
    }

    @Bean
    ScriptedProvider demoProvider(CallLog callLog) {
        return new ScriptedProvider("demo", 10, true, callLog);
    }

    @Bean
    ScriptedProvider demoLateProvider(CallLog callLog) {
        return new ScriptedProvider("demo_late", 20, false, callLog);
    }

    @Bean
    @Primary
    SettableClock trashTestClock() {
        return new SettableClock();
    }

    @Bean
    static BeanPostProcessor trashTestMessages() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if ("messageSource".equals(beanName) && bean instanceof HierarchicalMessageSource source) {
                    StaticMessageSource parent = new StaticMessageSource();
                    parent.addMessage(LOCATION, Locale.ENGLISH, "Demo folder");
                    parent.addMessage(LOCATION, Locale.of("vi"), "Thư mục demo");
                    parent.addMessage(NAME_TAKEN, Locale.ENGLISH, "The name is taken");
                    parent.addMessage(NAME_TAKEN, Locale.of("vi"), "Tên đã được dùng");
                    parent.addMessage(IN_USE, Locale.ENGLISH, "The item is in use");
                    parent.addMessage(IN_USE, Locale.of("vi"), "Mục đang được dùng");
                    source.setParentMessageSource(parent);
                }
                return bean;
            }
        };
    }

    /** Every provider call of both providers in the order made, as {@code <type>:<restore|purge>:<id>}. */
    public static final class CallLog {

        private final List<String> entries = Collections.synchronizedList(new ArrayList<>());

        void add(String entry) {
            entries.add(entry);
        }

        public List<String> entries() {
            synchronized (entries) {
                return List.copyOf(entries);
            }
        }

        public void clear() {
            entries.clear();
        }
    }

    /** A clock a test sets; the app's {@code Clock} while this configuration is imported. */
    public static final class SettableClock extends Clock {

        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

        public void set(Instant instant) {
            now.set(instant);
        }

        public void advance(Duration by) {
            now.updateAndGet(current -> current.plus(by));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("fixed to UTC");
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    /**
     * A provider whose rows live in a map. Each item answers like the real thing unless a test scripts it to answer
     * {@code Conflict}/{@code Blocked}/{@code Gone} or to throw. Writes register a rollback synchronization, so a
     * rolled-back Trash transaction leaves the map as it was; a purge registers its "file delete" after commit only.
     */
    public static final class ScriptedProvider implements TrashProvider {

        private record Row(UUID owner, boolean deleted) {
        }

        private final String type;
        private final int rank;
        private final boolean reminds;
        private final Map<String, Row> rows = new ConcurrentHashMap<>();
        private final Map<String, RestoreOutcome> restoreAnswers = new ConcurrentHashMap<>();
        private final Map<String, PurgeOutcome> purgeAnswers = new ConcurrentHashMap<>();
        private final Map<String, RuntimeException> restoreFailures = new ConcurrentHashMap<>();
        private final Map<String, RuntimeException> purgeFailures = new ConcurrentHashMap<>();
        private final Map<String, RuntimeException> purgeFailuresAfterWrite = new ConcurrentHashMap<>();
        private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        private final List<String> fileDeletes = Collections.synchronizedList(new ArrayList<>());

        private final CallLog callLog;

        ScriptedProvider(String type, int rank, boolean reminds, CallLog callLog) {
            this.type = type;
            this.rank = rank;
            this.reminds = reminds;
            this.callLog = callLog;
        }

        @Override
        public String type() {
            return type;
        }

        @Override
        public int purgeRank() {
            return rank;
        }

        @Override
        public boolean remindsBeforeExpiry() {
            return reminds;
        }

        @Override
        public RestoreOutcome restore(TrashItemRef item) {
            String id = item.itemId();
            calls.add("restore:" + id);
            callLog.add(type + ":restore:" + id);
            throwIfScripted(restoreFailures, id);
            RestoreOutcome scripted = restoreAnswers.get(id);
            if (scripted != null) {
                return scripted;
            }
            Row row = rows.get(id);
            if (row == null || !row.deleted() || !row.owner().equals(item.ownerId())) {
                return new RestoreOutcome.Gone();
            }
            rows.put(id, new Row(row.owner(), false));
            onRollback(() -> rows.put(id, row));
            return new RestoreOutcome.Restored();
        }

        @Override
        public PurgeOutcome purge(TrashItemRef item) {
            String id = item.itemId();
            calls.add("purge:" + id);
            callLog.add(type + ":purge:" + id);
            throwIfScripted(purgeFailures, id);
            PurgeOutcome scripted = purgeAnswers.get(id);
            if (scripted != null) {
                return scripted;
            }
            Row row = rows.get(id);
            if (row == null || !row.deleted() || !row.owner().equals(item.ownerId())) {
                return new PurgeOutcome.Gone();
            }
            rows.remove(id);
            onRollback(() -> rows.put(id, row));
            onCommit(() -> fileDeletes.add(id));
            throwIfScripted(purgeFailuresAfterWrite, id);
            return new PurgeOutcome.Purged();
        }

        /** Arranges a soft-deleted row of {@code owner}. */
        public void trash(String itemId, UUID owner) {
            rows.put(itemId, new Row(owner, true));
        }

        public boolean hasRow(String itemId) {
            return rows.containsKey(itemId);
        }

        public boolean isDeleted(String itemId) {
            Row row = rows.get(itemId);
            return row != null && row.deleted();
        }

        public void answerRestore(String itemId, RestoreOutcome outcome) {
            restoreAnswers.put(itemId, outcome);
        }

        public void answerPurge(String itemId, PurgeOutcome outcome) {
            purgeAnswers.put(itemId, outcome);
        }

        public void failRestore(String itemId, RuntimeException failure) {
            restoreFailures.put(itemId, failure);
        }

        public void failPurge(String itemId, RuntimeException failure) {
            purgeFailures.put(itemId, failure);
        }

        /** The purge deletes the row and registers its file delete, then throws. */
        public void failPurgeAfterWrite(String itemId, RuntimeException failure) {
            purgeFailuresAfterWrite.put(itemId, failure);
        }

        /** Every {@code restore:<id>} and {@code purge:<id>} call, in the order made. */
        public List<String> calls() {
            synchronized (calls) {
                return List.copyOf(calls);
            }
        }

        /** The ids whose after-commit file delete ran. */
        public List<String> fileDeletes() {
            synchronized (fileDeletes) {
                return List.copyOf(fileDeletes);
            }
        }

        public void reset() {
            rows.clear();
            restoreAnswers.clear();
            purgeAnswers.clear();
            restoreFailures.clear();
            purgeFailures.clear();
            purgeFailuresAfterWrite.clear();
            calls.clear();
            fileDeletes.clear();
        }

        private static void throwIfScripted(Map<String, RuntimeException> failures, String id) {
            RuntimeException failure = failures.get(id);
            if (failure != null) {
                throw failure;
            }
        }

        private static void onRollback(Runnable action) {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status == STATUS_ROLLED_BACK) {
                            action.run();
                        }
                    }
                });
            }
        }

        private static void onCommit(Runnable action) {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        action.run();
                    }
                });
            }
        }
    }
}
