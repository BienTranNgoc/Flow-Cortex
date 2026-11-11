package com.flowcortex.settings.application;

import com.flowcortex.settings.api.SettingChange;
import com.flowcortex.settings.api.SettingDefinition;
import com.flowcortex.settings.api.SettingKey;
import com.flowcortex.settings.api.SettingRead;
import com.flowcortex.settings.api.SettingSource;
import com.flowcortex.settings.api.SettingType;
import com.flowcortex.settings.api.Settings;
import com.flowcortex.settings.infrastructure.SettingStore;
import com.flowcortex.settings.infrastructure.SettingStore.StoredChange;
import com.flowcortex.settings.infrastructure.SettingStore.StoredSetting;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.PageView;
import com.flowcortex.shared.web.UnprocessableEntityException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Reads decide through {@link SettingResolution} and never throw for a store problem; writes take a
 * per-key lock and commit the value with its history row, or neither.
 */
@Service
public class SettingsService implements Settings {

    private static final Logger LOG = LoggerFactory.getLogger(SettingsService.class);

    private final SettingStore store;
    private final SettingsRegistry registry;
    private final MeterRegistry meters;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;

    public SettingsService(SettingStore store, SettingsRegistry registry, PlatformTransactionManager transactions,
            MeterRegistry meters) {
        this.store = store;
        this.registry = registry;
        this.meters = meters;
        this.writes = new TransactionTemplate(transactions);
        // A read outside the caller's transaction: a failed query must never abort one the caller holds.
        this.reads = new TransactionTemplate(transactions);
        this.reads.setPropagationBehavior(TransactionTemplate.PROPAGATION_NOT_SUPPORTED);
    }

    @Override
    public <T> SettingRead<T> get(SettingKey<T> key) {
        return snapshot(definitionOf(key)).read();
    }

    @Override
    public Optional<SettingChange> lastChange(SettingKey<?> key) {
        SettingDefinition<?> definition = definitionOf(key);
        try {
            return reads.execute(status -> store.find(definition.name()))
                    .map(stored -> new SettingChange(stored.updatedBy(), stored.updatedAt()));
        } catch (DataAccessException | TransactionException e) {
            LOG.error("settings.lastChange outcome=degraded key={}", definition.name(), e);
            return Optional.empty();
        }
    }

    @Override
    public <T> void set(SettingKey<T> key, T value, UUID actor) {
        SettingDefinition<T> definition = definitionOf(key);
        JsonNode node = value instanceof Boolean flag
                ? JsonNodeFactory.instance.booleanNode(flag)
                : value instanceof Integer number ? JsonNodeFactory.instance.numberNode(number) : null;
        if (!(SettingValues.parseRequest(definition, node) instanceof SettingValues.Accepted accepted)) {
            throw new IllegalArgumentException("Value " + value + " is not allowed for setting " + key.name());
        }
        write(definition.name(), accepted.text(), actor);
    }

    /** Stores a request value for the named key; unknown key is a 404, a refused value a 422. */
    public SettingView setRaw(String name, JsonNode value, UUID actor) {
        SettingDefinition<?> definition = require(name);
        if (!(SettingValues.parseRequest(definition, value) instanceof SettingValues.Accepted accepted)) {
            throw refusal(SettingValues.parseRequest(definition, value));
        }
        write(name, accepted.text(), actor);
        return view(name);
    }

    /** Removes the stored value so the layers beneath decide again; a key with nothing stored changes nothing. */
    public SettingView reset(String name, UUID actor) {
        require(name);
        write(name, null, actor);
        return view(name);
    }

    /** Every declared key, in key order. */
    public List<SettingView> views() {
        return registry.all().stream().map(this::viewOf).toList();
    }

    public SettingView view(String name) {
        return viewOf(require(name));
    }

    /** A page of a key's changes, newest first. */
    public PageView<SettingHistoryEntry> history(String name, int page, int size) {
        SettingDefinition<?> definition = require(name);
        List<StoredChange> rows = store.history(name, page, size);
        long total = store.countHistory(name);
        List<SettingHistoryEntry> items = rows.stream()
                .map(row -> new SettingHistoryEntry(typed(definition, row.previousValue()),
                        typed(definition, row.newValue()), row.changedBy(), row.changedAt()))
                .toList();
        return new PageView<>(items, page, size, total);
    }

    private SettingView viewOf(SettingDefinition<?> definition) {
        return snapshot(definition).view();
    }

    private record Snapshot<T>(SettingDefinition<T> definition, SettingRead<T> read, T storedValue,
            SettingChange change) {

        SettingView view() {
            return new SettingView(definition, read, storedValue, change);
        }
    }

    private <T> Snapshot<T> snapshot(SettingDefinition<T> definition) {
        Optional<StoredSetting> stored = Optional.empty();
        boolean failed = false;
        try {
            stored = Objects.requireNonNull(reads.execute(status -> store.find(definition.name())));
        } catch (DataAccessException | TransactionException e) {
            failed = true;
            LOG.error("settings.read outcome=degraded key={}", definition.name(), e);
        }
        SettingRead<T> read = SettingResolution.resolve(definition, stored.map(StoredSetting::value), failed);
        if (read.source() == SettingSource.DEGRADED) {
            meters.counter("settings.read.degraded", "key", definition.name()).increment();
            if (!failed) {
                LOG.error("settings.read outcome=degraded key={}", definition.name());
            }
        }
        T storedValue = stored.flatMap(row -> SettingValues.parseStored(definition, row.value())).orElse(null);
        SettingChange change = stored.map(row -> new SettingChange(row.updatedBy(), row.updatedAt())).orElse(null);
        return new Snapshot<>(definition, read, storedValue, change);
    }

    private void write(String name, String newText, UUID actor) {
        writes.executeWithoutResult(status -> {
            store.lockKey(name);
            String previous = store.find(name).map(StoredSetting::value).orElse(null);
            if (Objects.equals(previous, newText)) {
                return;
            }
            Instant now = Instant.now();
            if (newText == null) {
                store.delete(name);
            } else {
                store.upsert(name, newText, actor, now);
            }
            store.insertChange(UUID.randomUUID(), name, previous, newText, actor, now);
            LOG.info("settings.write outcome={} key={}", newText == null ? "reset" : "updated", name);
        });
    }

    private SettingDefinition<?> require(String name) {
        return registry.definition(name)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("settings.notFound", name)));
    }

    @SuppressWarnings("unchecked")
    private <T> SettingDefinition<T> definitionOf(SettingKey<T> key) {
        SettingDefinition<?> definition = registry.definition(key.name())
                .orElseThrow(() -> new IllegalArgumentException("Setting " + key.name() + " is not declared"));
        if (definition.key().type() != key.type()) {
            throw new IllegalArgumentException("Setting " + key.name() + " is declared as "
                    + definition.key().type().getSimpleName() + ", not " + key.type().getSimpleName());
        }
        return (SettingDefinition<T>) definition;
    }

    private static <T> Object typed(SettingDefinition<T> definition, String text) {
        return SettingValues.parseStored(definition, text).orElse(null);
    }

    private static UnprocessableEntityException refusal(SettingValues.RequestValue outcome) {
        SettingValues.Refused refused = (SettingValues.Refused) outcome;
        LocalizedMessage reason = refused.type() == SettingType.SWITCH
                ? new LocalizedMessage("settings.value.switch")
                : new LocalizedMessage("settings.value.integerRange", refused.min(), refused.max());
        return new UnprocessableEntityException(new LocalizedMessage("settings.invalidValue"), "setting.invalidValue",
                Map.of("value", reason));
    }
}
