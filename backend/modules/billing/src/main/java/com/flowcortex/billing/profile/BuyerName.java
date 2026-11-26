package com.flowcortex.billing.profile;

import java.util.Optional;

/** The buyer name as an invoice prints it: emoji removed, whitespace collapsed, nothing left means no name. */
final class BuyerName {

    private BuyerName() {
    }

    static Optional<String> readable(String name) {
        if (name == null) {
            return Optional.empty();
        }
        StringBuilder kept = new StringBuilder(name.length());
        name.codePoints().filter(point -> !isPictograph(point)).forEach(kept::appendCodePoint);
        String collapsed = kept.toString().replaceAll("\\s+", " ").strip();
        return collapsed.isEmpty() ? Optional.empty() : Optional.of(collapsed);
    }

    private static boolean isPictograph(int point) {
        return (point >= 0x1F300 && point <= 0x1FAFF)
                || (point >= 0x2600 && point <= 0x27BF)
                || (point >= 0xFE00 && point <= 0xFE0F)
                || (point >= 0x1F000 && point <= 0x1F02F)
                || point == 0x200D
                || point == 0x20E3;
    }
}
