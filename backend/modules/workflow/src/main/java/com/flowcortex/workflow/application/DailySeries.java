package com.flowcortex.workflow.application;

import com.flowcortex.workflow.api.DayCount;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Day buckets in the product's day zone; the window is {@code days} days ending on {@code today}. */
public final class DailySeries {

    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private DailySeries() {
    }

    public static LocalDate dayOf(Instant instant) {
        return instant.atZone(ZONE).toLocalDate();
    }

    /** The first instant of the window's oldest day. */
    public static Instant windowStart(LocalDate today, int days) {
        return today.minusDays(days - 1L).atStartOfDay(ZONE).toInstant();
    }

    /** The window's days, oldest first. */
    public static List<LocalDate> days(LocalDate today, int n) {
        List<LocalDate> days = new ArrayList<>(n);
        for (int offset = n - 1; offset >= 0; offset--) {
            days.add(today.minusDays(offset));
        }
        return days;
    }

    /** Exactly {@code days} points, zero where {@code rows} has none; rows outside the window are dropped. */
    public static List<DayCount> fill(List<DayCount> rows, LocalDate today, int days) {
        Map<LocalDate, DayCount> byDay = new HashMap<>();
        for (DayCount row : rows) {
            byDay.put(row.day(), row);
        }
        List<DayCount> series = new ArrayList<>(days);
        for (LocalDate day : days(today, days)) {
            series.add(byDay.getOrDefault(day, new DayCount(day, 0, 0)));
        }
        return series;
    }
}
