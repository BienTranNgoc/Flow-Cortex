package com.flowcortex.settings.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;

import com.flowcortex.settings.api.SettingDefinition;
import com.flowcortex.settings.api.SettingKey;
import com.flowcortex.settings.api.SettingRead;
import com.flowcortex.settings.api.SettingSource;
import com.flowcortex.settings.infrastructure.SettingStore;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.PageView;
import com.flowcortex.shared.web.UnprocessableEntityException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.node.JsonNodeFactory;

class SettingsServiceTest {

    private static final String SWITCH = "support.assistant.enabled";
    private static final String LIMIT = "support.assistant.daily_limit";
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final MemoryStore store = new MemoryStore();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final SettingsService service = new SettingsService(store,
            new SettingsRegistry(List.of(SettingDefinition.integerKey(LIMIT, 1, 100, () -> 20),
                    SettingDefinition.switchKey(SWITCH, true, () -> null))),
            mock(PlatformTransactionManager.class), meters);
    private final UUID admin = UUID.randomUUID();

    @Test
    void aKeyWithNothingStoredTakesTheLayersBeneath() {
        assertThat(service.get(SettingKey.switchKey(SWITCH)))
                .isEqualTo(new SettingRead<>(true, true, SettingSource.DEFAULT));
        assertThat(service.get(SettingKey.integerKey(LIMIT)))
                .isEqualTo(new SettingRead<>(20, 20, SettingSource.CONFIGURED));
        assertThat(service.lastChange(SettingKey.switchKey(SWITCH))).isEmpty();
    }

    @Test
    void aStoredValueWinsAndCarriesItsStamp() {
        SettingView view = service.setRaw(SWITCH, JSON.booleanNode(false), admin);

        assertThat(view.read()).isEqualTo(new SettingRead<>(false, false, SettingSource.STORED));
        assertThat(view.storedValue()).isEqualTo(false);
        assertThat(view.change().actor()).isEqualTo(admin);
        assertThat(store.values.get(SWITCH).value()).isEqualTo("false");
        assertThat(service.lastChange(SettingKey.switchKey(SWITCH))).hasValueSatisfying(
                change -> assertThat(change.actor()).isEqualTo(admin));
    }

    @Test
    void storingTheSameValueAgainRecordsOneChange() {
        service.setRaw(LIMIT, JSON.numberNode(42), admin);
        service.setRaw(LIMIT, JSON.numberNode(42), admin);

        assertThat(store.changes).hasSize(1);
        assertThat(store.changes.getFirst().previousValue()).isNull();
        assertThat(store.changes.getFirst().newValue()).isEqualTo("42");
    }

    @Test
    void resetRemovesTheValueAndRecordsWhatItWas() {
        service.setRaw(LIMIT, JSON.numberNode(42), admin);

        SettingView view = service.reset(LIMIT, admin);

        assertThat(view.read().source()).isEqualTo(SettingSource.CONFIGURED);
        assertThat(view.storedValue()).isNull();
        assertThat(view.change()).isNull();
        assertThat(store.values).doesNotContainKey(LIMIT);
        assertThat(store.changes).hasSize(2);
        assertThat(store.changes.getLast().previousValue()).isEqualTo("42");
        assertThat(store.changes.getLast().newValue()).isNull();
    }

    @Test
    void resettingAKeyWithNothingStoredChangesNothing() {
        service.reset(SWITCH, admin);

        assertThat(store.changes).isEmpty();
    }

    @Test
    void aRefusedRequestValueIsUnprocessableAndStoresNothing() {
        assertThatThrownBy(() -> service.setRaw(SWITCH, JSON.numberNode(1), admin))
                .isInstanceOf(UnprocessableEntityException.class)
                .satisfies(e -> assertThat(((UnprocessableEntityException) e).getErrors().get("value").code())
                        .isEqualTo("settings.value.switch"));
        assertThatThrownBy(() -> service.setRaw(LIMIT, JSON.numberNode(101), admin))
                .isInstanceOf(UnprocessableEntityException.class)
                .satisfies(e -> assertThat(((UnprocessableEntityException) e).getErrors().get("value").code())
                        .isEqualTo("settings.value.integerRange"));

        assertThat(store.values).isEmpty();
        assertThat(store.changes).isEmpty();
    }

    @Test
    void anUndeclaredNameIsNotFound() {
        String unknown = "support.assistant.unknown";
        assertThatThrownBy(() -> service.view(unknown)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.setRaw(unknown, JSON.booleanNode(true), admin))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.reset(unknown, admin)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.history(unknown, 0, 10)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aTypedWriteStoresTheCanonicalTextAndRefusesAValueOutsideTheBounds() {
        service.set(SettingKey.integerKey(LIMIT), 7, admin);
        service.set(SettingKey.switchKey(SWITCH), true, null);

        assertThat(store.values.get(LIMIT).value()).isEqualTo("7");
        assertThat(store.values.get(SWITCH).value()).isEqualTo("true");
        assertThat(store.values.get(SWITCH).updatedBy()).isNull();
        assertThatThrownBy(() -> service.set(SettingKey.integerKey(LIMIT), 0, admin))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(LIMIT);
    }

    @Test
    void aKeyThatIsUndeclaredOrOfAnotherTypeIsRefused() {
        assertThatThrownBy(() -> service.get(SettingKey.switchKey("support.assistant.unknown")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not declared");
        assertThatThrownBy(() -> service.get(SettingKey.integerKey(SWITCH)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Boolean");
    }

    @Test
    void aFailedStoreReadDegradesInsteadOfThrowing() {
        store.down = true;

        SettingView view = service.view(SWITCH);

        assertThat(view.read()).isEqualTo(new SettingRead<>(false, false, SettingSource.DEGRADED));
        assertThat(view.storedValue()).isNull();
        assertThat(service.lastChange(SettingKey.switchKey(SWITCH))).isEmpty();
        assertThat(meters.counter("settings.read.degraded", "key", SWITCH).count()).isEqualTo(1.0);
    }

    @Test
    void anUnparseableStoredValueDegradesToTheSafePosition() {
        store.values.put(LIMIT, new SettingStore.StoredSetting("abc", admin, Instant.now()));

        SettingView view = service.view(LIMIT);

        assertThat(view.read()).isEqualTo(new SettingRead<>(20, 20, SettingSource.DEGRADED));
        assertThat(view.storedValue()).isNull();
        assertThat(view.change()).isNotNull();
        assertThat(meters.counter("settings.read.degraded", "key", LIMIT).count()).isEqualTo(1.0);
    }

    @Test
    void historyReadsTypedValuesNewestFirstWithTheTotal() {
        service.setRaw(LIMIT, JSON.numberNode(5), admin);
        service.setRaw(LIMIT, JSON.numberNode(6), admin);
        service.reset(LIMIT, admin);

        PageView<SettingHistoryEntry> page = service.history(LIMIT, 0, 2);

        assertThat(page.total()).isEqualTo(3);
        assertThat(page.items()).extracting(SettingHistoryEntry::previousValue, SettingHistoryEntry::newValue)
                .containsExactly(tuple(6, null), tuple(5, 6));
    }

    @Test
    void viewsListEveryDeclaredKeyInKeyOrder() {
        assertThat(service.views()).extracting(view -> view.definition().name()).containsExactly(LIMIT, SWITCH);
    }

    private static final class MemoryStore extends SettingStore {

        private final Map<String, StoredSetting> values = new HashMap<>();
        private final List<StoredChange> changes = new ArrayList<>();
        private boolean down;

        MemoryStore() {
            super(null);
        }

        @Override
        public Optional<StoredSetting> find(String key) {
            if (down) {
                throw new DataAccessResourceFailureException("store unavailable");
            }
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public void lockKey(String key) {
        }

        @Override
        public void upsert(String key, String value, UUID actor, Instant at) {
            values.put(key, new StoredSetting(value, actor, at));
        }

        @Override
        public void delete(String key) {
            values.remove(key);
        }

        @Override
        public void insertChange(UUID id, String key, String previous, String next, UUID actor, Instant at) {
            changes.add(new StoredChange(previous, next, actor, at));
        }

        @Override
        public List<StoredChange> history(String key, int page, int size) {
            return changes.reversed().stream()
                    .sorted(Comparator.comparing(StoredChange::changedAt).reversed())
                    .skip((long) page * size)
                    .limit(size)
                    .toList();
        }

        @Override
        public long countHistory(String key) {
            return changes.size();
        }
    }
}
