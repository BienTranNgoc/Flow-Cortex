package com.flowcortex.notification.application;

import com.flowcortex.notification.infrastructure.NotificationRepository;
import com.flowcortex.notification.infrastructure.NotificationRepository.NotificationRow;
import com.flowcortex.notification.infrastructure.NotificationRepository.RowPage;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The member's reads and writes on their own inbox. A change that touched no row signals nothing.
 */
@Service
@Transactional
public class NotificationService {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 50;

    private final NotificationRepository repository;
    private final NotificationText text;
    private final InboxSignals signals;
    private final Clock clock;

    NotificationService(NotificationRepository repository, NotificationText text, InboxSignals signals, Clock clock) {
        this.repository = repository;
        this.text = text;
        this.signals = signals;
        this.clock = clock;
    }

    /** One rendered item of {@link Listing}. */
    public record Item(UUID id, String type, String title, String body, String link, boolean read,
            Instant createdAt) {
    }

    /** A page of the member's notifications with the count of all their unread ones. */
    public record Listing(List<Item> items, int page, int size, boolean hasMore, long unreadCount) {
    }

    /**
     * @param filter {@code all} or {@code unread}; null means {@code all}
     * @param page   zero-based page, as received
     * @param size   page size 1-50, as received
     * @throws LocalizedStatusException 400 naming the first parameter that is out of range
     */
    @Transactional(readOnly = true)
    public Listing list(UUID userId, String filter, String page, String size, Locale locale) {
        boolean unreadOnly = parseFilter(filter);
        int pageNumber = parseInt(page, "page", 0, Integer.MAX_VALUE / MAX_SIZE, 0);
        int pageSize = parseInt(size, "size", 1, MAX_SIZE, DEFAULT_SIZE);
        RowPage rows = repository.page(userId, unreadOnly, pageNumber * pageSize, pageSize);
        List<Item> items = rows.rows().stream().map(row -> item(row, locale)).toList();
        return new Listing(items, pageNumber, pageSize, rows.hasMore(), repository.unreadCount(userId));
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        return repository.unreadCount(userId);
    }

    /** @return false when the id is unknown or not the member's */
    public boolean markRead(UUID userId, UUID id) {
        if (repository.markRead(id, userId, Instant.now(clock)) == 1) {
            signals.changed(userId);
            return true;
        }
        return repository.exists(id, userId);
    }

    /** @return how many unread notifications were marked read */
    public int markAllRead(UUID userId) {
        int marked = repository.markAllRead(userId, Instant.now(clock));
        if (marked > 0) {
            signals.changed(userId);
        }
        return marked;
    }

    private Item item(NotificationRow row, Locale locale) {
        return new Item(row.id(), row.type(), text.title(row.titleCode(), row.args(), locale),
                text.body(row.bodyCode(), row.args(), locale), row.link(), row.readAt() != null, row.createdAt());
    }

    private static boolean parseFilter(String filter) {
        if (filter == null || filter.equals("all")) {
            return false;
        }
        if (filter.equals("unread")) {
            return true;
        }
        throw invalid(filter, "filter");
    }

    private static int parseInt(String value, String name, int min, int max, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= min && parsed <= max) {
                return parsed;
            }
        } catch (NumberFormatException e) {
            // reported below as the same 400 as an out-of-range number
        }
        throw invalid(value, name);
    }

    private static LocalizedStatusException invalid(String value, String name) {
        return new LocalizedStatusException(HttpStatus.BAD_REQUEST,
                new LocalizedMessage("notification.page.invalid", value, name));
    }
}
