package com.flowcortex.settings.application;

import com.flowcortex.settings.api.SettingDefinition;
import com.flowcortex.settings.api.SettingType;
import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/** Turns request JSON and stored text into values of a declared type, and refuses everything else. */
public final class SettingValues {

    /** Canonical decimal text: no sign, no leading zero, no fraction. */
    private static final Pattern DECIMAL = Pattern.compile("0|-?[1-9][0-9]*");

    private SettingValues() {
    }

    /** The outcome of validating a request value. */
    public sealed interface RequestValue permits Accepted, Refused {
    }

    /** A value of the declared type and within its bounds, as the canonical text that is stored. */
    public record Accepted(String text) implements RequestValue {
    }

    /** A value refused; carries the expected type and, for an integer, its inclusive bounds. */
    public record Refused(SettingType type, Integer min, Integer max) implements RequestValue {
    }

    public static RequestValue parseRequest(SettingDefinition<?> definition, JsonNode node) {
        Refused refusal = new Refused(definition.type(), definition.min(), definition.max());
        if (node == null) {
            return refusal;
        }
        if (definition.type() == SettingType.SWITCH) {
            return node.isBoolean() ? new Accepted(Boolean.toString(node.booleanValue())) : refusal;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            return refusal;
        }
        int value = node.intValue();
        return withinBounds(definition, value) ? new Accepted(Integer.toString(value)) : refusal;
    }

    /** The stored text read back as the declared type; empty when it is not its canonical text. */
    public static <T> Optional<T> parseStored(SettingDefinition<T> definition, String text) {
        if (text == null) {
            return Optional.empty();
        }
        Class<T> javaType = definition.key().type();
        if (definition.type() == SettingType.SWITCH) {
            return switch (text) {
                case "true" -> Optional.of(javaType.cast(Boolean.TRUE));
                case "false" -> Optional.of(javaType.cast(Boolean.FALSE));
                default -> Optional.empty();
            };
        }
        if (!DECIMAL.matcher(text).matches()) {
            return Optional.empty();
        }
        try {
            int value = Integer.parseInt(text);
            return withinBounds(definition, value) ? Optional.of(javaType.cast(value)) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static boolean withinBounds(SettingDefinition<?> definition, int value) {
        return value >= definition.min() && value <= definition.max();
    }
}
