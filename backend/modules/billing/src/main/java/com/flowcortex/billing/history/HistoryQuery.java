package com.flowcortex.billing.history;

import java.util.regex.Pattern;

/**
 * The paging and filter of a history read. {@code page} counts from 0; {@code settled} keeps only payments
 * where money reached us.
 */
public record HistoryQuery(int page, int size, boolean settled) {

    static final int MAX_PAGE = 10_000;
    static final int MAX_SIZE = 100;
    static final int DEFAULT_SIZE = 20;

    private static final Pattern WHOLE_NUMBER = Pattern.compile("[0-9]{1,9}");

    /** An absent or empty value takes its default; anything else out of range is refused. */
    static HistoryQuery parse(String page, String size, String settled) {
        int pageNumber = wholeNumber(page, 0, 0, MAX_PAGE);
        int pageSize = wholeNumber(size, DEFAULT_SIZE, 1, MAX_SIZE);
        return new HistoryQuery(pageNumber, pageSize, flag(settled));
    }

    private static int wholeNumber(String value, int fallback, int min, int max) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        if (!WHOLE_NUMBER.matcher(value).matches()) {
            throw InvalidTransactionQueryException.pagination();
        }
        int parsed = Integer.parseInt(value);
        if (parsed < min || parsed > max) {
            throw InvalidTransactionQueryException.pagination();
        }
        return parsed;
    }

    private static boolean flag(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        return switch (value) {
            case "true", "1" -> true;
            case "false", "0" -> false;
            default -> throw InvalidTransactionQueryException.flag();
        };
    }

    long offset() {
        return (long) page * size;
    }
}
