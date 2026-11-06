package com.flowcortex.app.support.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.support.assistant.AssistantRetentionJob;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class AssistantRetentionJobIT extends AbstractPostgresBootTest {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired
    private AssistantRetentionJob job;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void empty() {
        jdbcTemplate.update("DELETE FROM support_ai_conversation");
        jdbcTemplate.update("DELETE FROM support_quota_usage");
    }

    @Test
    void aConversationIdleNinetyOneDaysIsDeletedAndItsRequestKeepsANullLink() {
        UUID expired = conversation(Duration.ofDays(91));
        UUID kept = conversation(Duration.ofDays(89));
        UUID request = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO support_request (id, requester_id, type, subject, status, ai_conversation_id, "
                + "member_unread, last_activity_at, created_at, updated_at) "
                + "VALUES (?, ?, 'other', 'Không đăng nhập được', 'new', ?, false, ?, ?, ?)",
                request, UUID.randomUUID(), expired, now, now, now);

        job.purge();

        assertThat(jdbcTemplate.queryForList("SELECT id FROM support_ai_conversation", UUID.class))
                .containsExactly(kept);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM support_ai_message WHERE conversation_id = ?",
                Integer.class, expired)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM support_ai_message WHERE conversation_id = ?",
                Integer.class, kept)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT ai_conversation_id FROM support_request WHERE id = ?",
                UUID.class, request)).isNull();
        jdbcTemplate.update("DELETE FROM support_request WHERE id = ?", request);
    }

    @Test
    void quotaRowsOlderThanTwoDaysAreDeleted() {
        LocalDate today = LocalDate.now(VIETNAM);
        for (int daysAgo = 0; daysAgo <= 3; daysAgo++) {
            jdbcTemplate.update("INSERT INTO support_quota_usage (subject_key, day, count) VALUES ('global', ?, 5)",
                    today.minusDays(daysAgo));
        }

        job.purge();

        assertThat(jdbcTemplate.queryForList("SELECT day FROM support_quota_usage ORDER BY day", LocalDate.class))
                .containsExactly(today.minusDays(2), today.minusDays(1), today);
    }

    private UUID conversation(Duration idle) {
        UUID id = UUID.randomUUID();
        Timestamp last = Timestamp.from(Instant.now().minus(idle));
        jdbcTemplate.update("INSERT INTO support_ai_conversation (id, owner_user_id, last_message_at, created_at, "
                + "updated_at) VALUES (?, ?, ?, ?, ?)", id, UUID.randomUUID(), last, last, last);
        jdbcTemplate.update("INSERT INTO support_ai_message (id, conversation_id, role, text, created_at) "
                + "VALUES (?, ?, 'user', 'Câu hỏi', ?)", UUID.randomUUID(), id, last);
        return id;
    }
}
