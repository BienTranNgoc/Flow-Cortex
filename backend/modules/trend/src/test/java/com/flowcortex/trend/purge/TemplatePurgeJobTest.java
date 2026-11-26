package com.flowcortex.trend.purge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.dao.DataAccessResourceFailureException;

class TemplatePurgeJobTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final Duration GRACE = Duration.ofDays(8);

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final TrendPurgeProperties properties = new TrendPurgeProperties(Duration.ofMinutes(15), GRACE, 20);
    private final List<String> calls = new ArrayList<>();
    private final RecordingStore store = new RecordingStore(calls);
    private final RecordingEffects effects = new RecordingEffects(calls);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger jobLog = (Logger) LoggerFactory.getLogger(TemplatePurgeJob.class);

    @BeforeEach
    void setUp() {
        logs.start();
        jobLog.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        jobLog.detachAppender(logs);
    }

    private TemplatePurgeJob job() {
        return new TemplatePurgeJob(provider(TemplatePurgeStore.class, store),
                provider(TemplatePurgeEffects.class, effects), properties, clock);
    }

    @Test
    void asksForTemplatesDeletedBeforeNowMinusGraceUpToTheBatch() {
        job().run();

        assertThat(store.askedDeletedBefore).isEqualTo(NOW.minus(GRACE));
        assertThat(store.askedLimit).isEqualTo(20);
    }

    @Test
    void purgesMediaThenEachAssetForgettingItThenTheRow() {
        store.due.add(new PurgeCandidate("t1", Set.of("b", "a")));
        effects.mediaCount.put("t1", 3);

        job().run();

        assertThat(calls).containsExactly("listDue", "claim:t1", "deleteMedia:t1", "deleteAsset:a", "forget:t1:a",
                "deleteAsset:b", "forget:t1:b", "deleteRow:t1");
        assertThat(messages()).containsExactly("trend.template.purged templateId=t1 objects=3 assets=2");
    }

    @Test
    void deletesAnAssetFiledTwiceOnce() {
        store.due.add(new PurgeCandidate("t1", Set.of("a")));

        job().run();

        assertThat(calls).filteredOn(call -> call.startsWith("deleteAsset:")).containsExactly("deleteAsset:a");
        assertThat(calls).filteredOn(call -> call.startsWith("forget:")).containsExactly("forget:t1:a");
    }

    @Test
    void skipsAnUnclaimedTemplateWithoutLogging() {
        store.due.add(new PurgeCandidate("t1", Set.of("a")));
        store.due.add(new PurgeCandidate("t2", Set.of()));
        store.unclaimable.add("t1");

        job().run();

        assertThat(calls).containsExactly("listDue", "claim:t1", "claim:t2", "deleteMedia:t2", "deleteRow:t2");
        assertThat(messages()).noneMatch(message -> message.contains("t1"));
        assertThat(messages()).containsExactly("trend.template.purged templateId=t2 objects=0 assets=0");
    }

    @Test
    void aMediaFailureIsLoggedWithItsCodeNotItsMessageAndReleased() {
        store.due.add(new PurgeCandidate("t1", Set.of("a")));
        store.due.add(new PurgeCandidate("t2", Set.of()));
        effects.mediaFailure.put("t1", new StorageFailure("AccessDenied", "cannot delete templates/t1/a.mp4"));

        job().run();

        assertThat(messages()).contains(
                "trend.template.purge_failed templateId=t1 step=media errorCode=AccessDenied",
                "trend.template.purged templateId=t2 objects=0 assets=0");
        assertThat(messages()).noneMatch(message -> message.contains("templates/"));
        assertThat(logs.list).noneMatch(event -> event.getThrowableProxy() != null);
        assertThat(calls).contains("release:t1").doesNotContain("deleteRow:t1", "deleteAsset:a");
    }

    @Test
    void anExceptionWithoutACodeLogsItsSimpleClassName() {
        store.due.add(new PurgeCandidate("t1", Set.of()));
        effects.mediaFailure.put("t1", new IllegalStateException("secret templates/t1/a.mp4"));

        job().run();

        assertThat(messages()).containsExactly(
                "trend.template.purge_failed templateId=t1 step=media errorCode=IllegalStateException");
    }

    @Test
    void aClaimThatThrowsIsLoggedWithStepClaimAndNotReleased() {
        store.due.add(new PurgeCandidate("t1", Set.of()));
        store.claimFailure = new DataAccessResourceFailureException("db down");

        job().run();

        assertThat(messages()).containsExactly(
                "trend.template.purge_failed templateId=t1 step=claim errorCode=DataAccessResourceFailureException");
        assertThat(calls).doesNotContain("release:t1");
    }

    @Test
    void aFailureOnTheSecondAssetKeepsTheFirstOneForgottenAndStopsBeforeTheRow() {
        store.due.add(new PurgeCandidate("t1", Set.of("a", "b")));
        effects.assetFailure.put("b", new StorageFailure("SlowDown", "retry later"));

        job().run();

        assertThat(calls).containsExactly("listDue", "claim:t1", "deleteMedia:t1", "deleteAsset:a", "forget:t1:a",
                "deleteAsset:b", "release:t1");
        assertThat(messages()).containsExactly(
                "trend.template.purge_failed templateId=t1 step=assets errorCode=SlowDown");
    }

    @Test
    void rowDeleteRemovingNothingIsRowNotDeleted() {
        store.due.add(new PurgeCandidate("t1", Set.of()));
        store.rowMissing.add("t1");

        job().run();

        assertThat(messages()).containsExactly(
                "trend.template.purge_failed templateId=t1 step=row errorCode=row_not_deleted");
        assertThat(calls).endsWith("deleteRow:t1", "release:t1");
    }

    @Test
    void aFailedReleaseIsLoggedWithStepRelease() {
        store.due.add(new PurgeCandidate("t1", Set.of()));
        store.due.add(new PurgeCandidate("t2", Set.of()));
        effects.mediaFailure.put("t1", new StorageFailure("AccessDenied", "x"));
        store.releaseFailure = new DataAccessResourceFailureException("db down");

        job().run();

        assertThat(messages()).contains(
                "trend.template.purge_failed templateId=t1 step=media errorCode=AccessDenied",
                "trend.template.purge_failed templateId=t1 step=release errorCode=DataAccessResourceFailureException");
        assertThat(calls).contains("deleteRow:t2");
    }

    @Test
    void aListingFailureReachesTheRunner() {
        store.listFailure = new DataAccessResourceFailureException("db down");

        assertThatThrownBy(() -> job().run()).isSameAs(store.listFailure);
    }

    @Test
    void doesNothingWithoutStoreOrEffects() {
        TemplatePurgeJob job = new TemplatePurgeJob(provider(TemplatePurgeStore.class),
                provider(TemplatePurgeEffects.class), properties, clock);

        job.run();
        job.run();

        assertThat(calls).isEmpty();
        assertThat(logs.list).isEmpty();
    }

    @Test
    void storeWithoutEffectsLogsOneWarningAcrossPasses() {
        store.due.add(new PurgeCandidate("t1", Set.of()));
        TemplatePurgeJob job = new TemplatePurgeJob(provider(TemplatePurgeStore.class, store),
                provider(TemplatePurgeEffects.class), properties, clock);

        job.run();
        job.run();
        job.run();

        assertThat(calls).isEmpty();
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(logs.list.get(0).getFormattedMessage())
                .isEqualTo("trend.template.purge_inert reason=effects_missing");
    }

    @Test
    void effectsWithoutStoreLogsOneWarningAcrossPasses() {
        TemplatePurgeJob job = new TemplatePurgeJob(provider(TemplatePurgeStore.class),
                provider(TemplatePurgeEffects.class, effects), properties, clock);

        job.run();
        job.run();

        assertThat(calls).isEmpty();
        assertThat(messages()).containsExactly("trend.template.purge_inert reason=store_missing");
    }

    @Test
    void twoStoresOrTwoEffectsRefuseConstruction() {
        assertThatThrownBy(() -> new TemplatePurgeJob(
                provider(TemplatePurgeStore.class, store, new RecordingStore(calls)),
                provider(TemplatePurgeEffects.class, effects), properties, clock))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TemplatePurgeStore");
        assertThatThrownBy(() -> new TemplatePurgeJob(
                provider(TemplatePurgeStore.class, store),
                provider(TemplatePurgeEffects.class, effects, new RecordingEffects(calls)), properties, clock))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TemplatePurgeEffects");
    }

    @Test
    void nameIsTrendTemplatePurgeAndIntervalComesFromProperties() {
        TemplatePurgeJob job = job();

        assertThat(job.name()).isEqualTo("trend.template-purge");
        assertThat(job.interval()).isEqualTo(Duration.ofMinutes(15));
    }

    private List<String> messages() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @SafeVarargs
    private static <T> ObjectProvider<T> provider(Class<T> type, T... beans) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        for (int i = 0; i < beans.length; i++) {
            factory.registerSingleton(type.getSimpleName() + i, beans[i]);
        }
        return factory.getBeanProvider(type);
    }

    private static final class StorageFailure extends RuntimeException implements TemplatePurgeFailure {

        private final String code;

        StorageFailure(String code, String message) {
            super(message);
            this.code = code;
        }

        @Override
        public String code() {
            return code;
        }
    }

    private static final class RecordingStore implements TemplatePurgeStore {

        final List<PurgeCandidate> due = new ArrayList<>();
        final Set<String> unclaimable = new HashSet<>();
        final Set<String> rowMissing = new HashSet<>();
        final List<String> calls;
        Instant askedDeletedBefore;
        int askedLimit;
        RuntimeException listFailure;
        RuntimeException claimFailure;
        RuntimeException releaseFailure;

        RecordingStore(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public List<PurgeCandidate> listDue(Instant deletedBefore, int limit) {
            calls.add("listDue");
            askedDeletedBefore = deletedBefore;
            askedLimit = limit;
            if (listFailure != null) {
                throw listFailure;
            }
            return List.copyOf(due);
        }

        @Override
        public boolean claim(String templateId) {
            calls.add("claim:" + templateId);
            if (claimFailure != null) {
                throw claimFailure;
            }
            return !unclaimable.contains(templateId);
        }

        @Override
        public void release(String templateId) {
            calls.add("release:" + templateId);
            if (releaseFailure != null) {
                throw releaseFailure;
            }
        }

        @Override
        public void forgetFiledAsset(String templateId, String assetId) {
            calls.add("forget:" + templateId + ":" + assetId);
        }

        @Override
        public boolean deleteRow(String templateId) {
            calls.add("deleteRow:" + templateId);
            return !rowMissing.contains(templateId);
        }
    }

    private static final class RecordingEffects implements TemplatePurgeEffects {

        final Map<String, Integer> mediaCount = new HashMap<>();
        final Map<String, RuntimeException> mediaFailure = new HashMap<>();
        final Map<String, RuntimeException> assetFailure = new HashMap<>();
        final List<String> calls;

        RecordingEffects(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public int deleteMedia(String templateId) {
            calls.add("deleteMedia:" + templateId);
            if (mediaFailure.containsKey(templateId)) {
                throw mediaFailure.get(templateId);
            }
            return mediaCount.getOrDefault(templateId, 0);
        }

        @Override
        public void deleteFiledAsset(String assetId) {
            calls.add("deleteAsset:" + assetId);
            if (assetFailure.containsKey(assetId)) {
                throw assetFailure.get(assetId);
            }
        }
    }
}
