package com.flowcortex.settings.application;

import com.flowcortex.settings.api.SettingDefinition;
import com.flowcortex.settings.api.SettingScope;
import com.flowcortex.settings.api.SettingType;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Every declared setting, collected from the feature modules at startup. Construction refuses, by
 * key name, any declaration the settings store must never hold.
 */
@Component
public class SettingsRegistry {

    private static final String SEGMENT = "[a-z][a-z0-9]*(?:_[a-z0-9]+)*";
    private static final Pattern KEY = Pattern.compile(SEGMENT + "(?:\\." + SEGMENT + "){2,}");
    private static final List<String> SECRET_MARKERS =
            List.of("secret", "password", "token", "api_key", "apikey", "credential", "private_key");

    private final Map<String, SettingDefinition<?>> byName;
    private final List<SettingDefinition<?>> sorted;

    /** Spring entry point; a context with no declaring module yet still starts. */
    @Autowired
    SettingsRegistry(ObjectProvider<SettingDefinition<?>> declarations) {
        this(declarations.orderedStream().toList());
    }

    public SettingsRegistry(List<SettingDefinition<?>> declarations) {
        Map<String, SettingDefinition<?>> names = new HashMap<>();
        for (SettingDefinition<?> definition : declarations) {
            String name = definition.name();
            requireWellFormed(name);
            if (names.putIfAbsent(name, definition) != null) {
                throw new IllegalStateException("Setting " + name + " is declared twice");
            }
            if (definition.type() == SettingType.INTEGER) {
                requireConfiguredWithinBounds(definition);
            } else if (definition.type() == SettingType.ENUM) {
                requireWellFormedEnum(definition);
            }
        }
        this.byName = Map.copyOf(names);
        this.sorted = declarations.stream().sorted(Comparator.comparing(SettingDefinition::name)).toList();
    }

    public Optional<SettingDefinition<?>> definition(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /** All declarations, ordered by key. */
    public List<SettingDefinition<?>> all() {
        return sorted;
    }

    /** The declarations of one scope, ordered by key. */
    public List<SettingDefinition<?>> all(SettingScope scope) {
        return sorted.stream().filter(definition -> definition.scope() == scope).toList();
    }

    private static void requireWellFormedEnum(SettingDefinition<?> definition) {
        String name = definition.name();
        List<String> options = definition.options();
        if (options.isEmpty()) {
            throw new IllegalStateException("Setting " + name + " is an enum with no options");
        }
        Set<String> seen = new HashSet<>();
        for (String option : options) {
            if (!seen.add(option)) {
                throw new IllegalStateException("Setting " + name + " has a duplicate option " + option);
            }
        }
        if (!options.contains(definition.codeDefault())) {
            throw new IllegalStateException("Setting " + name + " has code default " + definition.codeDefault()
                    + ", which is not one of its options " + options);
        }
    }

    private static void requireWellFormed(String name) {
        if (name == null || !KEY.matcher(name).matches()) {
            throw new IllegalStateException("Setting key " + name + " is malformed: expected three or more "
                    + "dot-separated lower snake case segments");
        }
        for (String segment : name.split("\\.")) {
            for (String marker : SECRET_MARKERS) {
                if (segment.contains(marker)) {
                    throw new IllegalStateException("Setting key " + name
                            + " looks like a secret, which the settings store never holds");
                }
            }
        }
    }

    private static void requireConfiguredWithinBounds(SettingDefinition<?> definition) {
        int min = definition.min();
        int max = definition.max();
        if (min > max) {
            throw new IllegalStateException("Setting " + definition.name() + " has bounds " + min + " to " + max
                    + ", the minimum is above the maximum");
        }
        Object configured = definition.configuredValue();
        if (!(configured instanceof Integer value) || value < min || value > max) {
            throw new IllegalStateException("Setting " + definition.name() + " is configured as " + configured
                    + ", outside its bounds " + min + " to " + max);
        }
    }
}
