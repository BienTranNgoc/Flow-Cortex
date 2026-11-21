package com.flowcortex.user.preferences;

import java.util.Optional;

/** A closed preference value written to JSON, and stored, under a lowercase wire name. */
interface WireValue {

    String wire();

    static <E extends Enum<E> & WireValue> Optional<E> parse(Class<E> type, String wire) {
        for (E constant : type.getEnumConstants()) {
            if (constant.wire().equals(wire)) {
                return Optional.of(constant);
            }
        }
        return Optional.empty();
    }
}
