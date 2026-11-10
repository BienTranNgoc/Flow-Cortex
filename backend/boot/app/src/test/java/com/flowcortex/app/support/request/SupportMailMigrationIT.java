package com.flowcortex.app.support.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.support.request.SupportReplyMailRequested;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The conversion of pending reply mails into registry publications, re-run against seeded rows: Flyway already
 * applied it to an empty table at boot, so the test executes the same classpath SQL again inside the test
 * transaction, which rolls everything back.
 */
@Testcontainers
@SpringBootTest
@Transactional
class SupportMailMigrationIT extends AbstractPostgresBootTest {

    private static final String MIGRATION = "db/migration/V2026.11.10.031__support_mail_outbox_to_event_publication.sql";
    private static final String LISTENER = "support.reply-mail";
    private static final Instant CREATED = Instant.parse("2026-11-10T08:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private EntityManager entityManager;

    private String conversion;
    private Pending twoAttempts;
    private Pending noAttempt;
    private Pending sent;
    private Pending sixAttempts;

    private record Pending(UUID outboxId, UUID requestId, UUID messageId, UUID recipient, long number) {
    }

    @BeforeEach
    void seed() throws IOException {
        try (InputStream in = new ClassPathResource(MIGRATION).getInputStream()) {
            conversion = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        twoAttempts = pending("en", 2, false, 0);
        noAttempt = pending("vi", 0, false, 1);
        sent = pending("vi", 1, true, 2);
        sixAttempts = pending("en", 6, false, 3);
    }

    private Pending pending(String locale, int attempts, boolean isSent, int minutesAfterStart) {
        Instant created = CREATED.plus(minutesAfterStart, ChronoUnit.MINUTES);
        Pending row = new Pending(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0);
        long number = jdbc.queryForObject("INSERT INTO support_request (id, requester_id, type, subject, status, "
                + "locale, member_unread, last_activity_at, created_at, updated_at) VALUES (?, ?, 'credit', "
                + "'Missing credit', 'waiting_member', ?, true, ?, ?, ?) RETURNING number", Long.class,
                row.requestId(), row.recipient(), locale, Timestamp.from(created), Timestamp.from(created),
                Timestamp.from(created));
        jdbc.update("INSERT INTO support_message (id, request_id, author_kind, author_id, body, created_at) "
                + "VALUES (?, ?, 'admin', ?, 'Checked.', ?)", row.messageId(), row.requestId(), UUID.randomUUID(),
                Timestamp.from(created));
        jdbc.update("INSERT INTO support_mail_outbox (id, request_id, message_id, recipient_user_id, attempts, "
                + "next_attempt_at, sent_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.outboxId(), row.requestId(), row.messageId(), row.recipient(), attempts, Timestamp.from(created),
                isSent ? Timestamp.from(created) : null, Timestamp.from(created), Timestamp.from(created));
        return new Pending(row.outboxId(), row.requestId(), row.messageId(), row.recipient(), number);
    }

    @Test
    void pendingRowsBecomeFailedPublicationsAndSentOrExhaustedRowsAreLeftBehind() {
        jdbc.update(conversion);

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, listener_id, event_type, status, "
                + "completion_attempts, publication_date, last_resubmission_date, completion_date, serialized_event "
                + "FROM event_publication WHERE id IN (?, ?, ?, ?) ORDER BY publication_date",
                twoAttempts.outboxId(), noAttempt.outboxId(), sent.outboxId(), sixAttempts.outboxId());

        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("listener_id")).isEqualTo(LISTENER);
            assertThat(row.get("event_type")).isEqualTo(SupportReplyMailRequested.class.getName());
            assertThat(row.get("status")).isEqualTo("FAILED");
            assertThat(row.get("last_resubmission_date")).isNull();
            assertThat(row.get("completion_date")).isNull();
        });
        assertThat(rows.get(0).get("id")).isEqualTo(twoAttempts.outboxId());
        assertThat(rows.get(0).get("completion_attempts")).isEqualTo(2);
        assertThat(rows.get(0).get("publication_date")).isEqualTo(Timestamp.from(CREATED));
        assertThat((String) rows.get(0).get("serialized_event")).contains("\"locale\":\"en\"")
                .contains("\"requestNumber\":" + twoAttempts.number());
        assertThat(rows.get(1).get("id")).isEqualTo(noAttempt.outboxId());
        assertThat(rows.get(1).get("completion_attempts")).isEqualTo(1);
        assertThat((String) rows.get(1).get("serialized_event")).contains("\"locale\":\"vi\"");
    }

    @Test
    void carriedRowsAreMarkedSentSoAnOldInstanceDoesNotMailThemAgain() {
        jdbc.update(conversion);

        assertThat(sentAt(twoAttempts)).isNotNull();
        assertThat(sentAt(noAttempt)).isNotNull();
        assertThat(sentAt(sixAttempts)).isNull();
    }

    @Test
    void aRowAnOldInstanceWritesAfterTheConversionIsCarriedByTheNextRun() {
        jdbc.update(conversion);
        Pending late = pending("en", 0, false, 4);

        jdbc.update(conversion);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_publication WHERE id = ?", Long.class,
                late.outboxId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_publication WHERE id IN (?, ?)", Long.class,
                twoAttempts.outboxId(), noAttempt.outboxId())).isEqualTo(2);
    }

    private Timestamp sentAt(Pending row) {
        return jdbc.queryForObject("SELECT sent_at FROM support_mail_outbox WHERE id = ?", Timestamp.class,
                row.outboxId());
    }

    @Test
    void aConvertedRowReadsLikeAPublishedOne() {
        jdbc.update(conversion);

        publisher.publishEvent(new SupportReplyMailRequested(twoAttempts.requestId(), twoAttempts.messageId(),
                twoAttempts.recipient(), twoAttempts.number(), "en"));
        entityManager.flush();

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, listener_id, event_type, serialized_event "
                + "FROM event_publication WHERE serialized_event LIKE ?", "%" + twoAttempts.requestId() + "%");
        assertThat(rows).hasSize(2);
        Map<String, Object> converted = rows.stream().filter(r -> r.get("id").equals(twoAttempts.outboxId()))
                .findFirst().orElseThrow();
        Map<String, Object> published = rows.stream().filter(r -> !r.get("id").equals(twoAttempts.outboxId()))
                .findFirst().orElseThrow();
        assertThat(published.get("listener_id")).isEqualTo(converted.get("listener_id"));
        assertThat(published.get("event_type")).isEqualTo(converted.get("event_type"));
        assertThat(published.get("serialized_event")).isEqualTo(converted.get("serialized_event"));
    }

    @Test
    void rerunningTheConversionCarriesNothingTwice() {
        jdbc.update(conversion);
        jdbc.update(conversion);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_publication WHERE id IN (?, ?)", Long.class,
                twoAttempts.outboxId(), noAttempt.outboxId())).isEqualTo(2);
    }
}
