package com.flowcortex.workflow.application;

import com.flowcortex.workflow.api.DayCount;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Daily run counts bucketed by the product's local day, zero-filled over a fixed window. */
public final class DailySeries {

    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private DailySeries() {
    }

    public static LocalDate dayOf(Instant instant) {
        return instant.atZone(ZONE).toLocalDate();
    }

    /** The first instant of the oldest day in a window of {@code days} days ending on {@code today}. */
    public static Instant windowStart(LocalDate today, int days) {
        return today.minusDays(days - 1L).atStartOfDay(ZONE).toInstant();
    }

    /** Exactly {@code days} points, oldest first, zero where {@code rows} has none; rows outside the window are dropped. */
    public static List<DayCount> fill(List<DayCount> rows, LocalDate today, int days) {
        Map<LocalDate, DayCount> byDay = new HashMap<>();
        for (DayCount row : rows) {
            byDay.merge(row.day(), row,
                    (a, b) -> new DayCount(a.day(), a.runs() + b.runs(), a.failed() + b.failed()));
        }
        List<DayCount> series = new ArrayList<>(days);
        for (int offset = days - 1; offset >= 0; offset--) {
            LocalDate day = today.minusDays(offset);
            series.add(byDay.getOrDefault(day, new DayCount(day, 0, 0)));
        }
        return series;
    }
}
