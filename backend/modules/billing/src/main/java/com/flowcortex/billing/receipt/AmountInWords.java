package com.flowcortex.billing.receipt;

import java.util.ArrayList;
import java.util.List;

/** Reads a whole amount of đồng in Vietnamese words, as the total on a receipt. */
final class AmountInWords {

    private static final String[] DIGITS = {"không", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín"};
    private static final String[] SCALES = {"", " nghìn", " triệu", " tỷ", " nghìn tỷ", " triệu tỷ"};
    private static final long BEYOND_SCALES = 1_000_000_000_000_000_000L;

    private AmountInWords() {
    }

    /**
     * "Một trăm nghìn đồng" for 100000. Every group after the leading one is read in full, a group of three
     * zeros is skipped.
     *
     * @throws IllegalArgumentException for a negative amount or one of 10^18 or more
     */
    static String vietnamese(long amountVnd) {
        if (amountVnd < 0 || amountVnd >= BEYOND_SCALES) {
            throw new IllegalArgumentException("The amount must be a whole number from 0 to below 10^18");
        }
        if (amountVnd == 0) {
            return "Không đồng";
        }
        List<Integer> groups = new ArrayList<>();
        for (long rest = amountVnd; rest > 0; rest /= 1000) {
            groups.add((int) (rest % 1000));
        }
        List<String> chunks = new ArrayList<>();
        for (int i = groups.size() - 1; i >= 0; i--) {
            if (groups.get(i) != 0) {
                chunks.add(group(groups.get(i), i != groups.size() - 1) + SCALES[i]);
            }
        }
        String text = String.join(" ", chunks);
        return Character.toUpperCase(text.charAt(0)) + text.substring(1) + " đồng";
    }

    /** One group of three digits; {@code full} when a larger group stands before it. */
    private static String group(int n, boolean full) {
        int hundreds = n / 100;
        int tens = n % 100 / 10;
        int units = n % 10;
        List<String> parts = new ArrayList<>();
        if (hundreds > 0 || full) {
            parts.add(DIGITS[hundreds] + " trăm");
        }
        if (tens == 0) {
            if (units > 0) {
                parts.add(hundreds > 0 || full ? "lẻ " + DIGITS[units] : DIGITS[units]);
            }
        } else if (tens == 1) {
            parts.add("mười");
            if (units == 5) {
                parts.add("lăm");
            } else if (units > 0) {
                parts.add(DIGITS[units]);
            }
        } else {
            parts.add(DIGITS[tens] + " mươi");
            if (units == 1) {
                parts.add("mốt");
            } else if (units == 5) {
                parts.add("lăm");
            } else if (units > 0) {
                parts.add(DIGITS[units]);
            }
        }
        return String.join(" ", parts);
    }
}
