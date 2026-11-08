package com.flowcortex.app.space;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.space.api.SessionSpaces;
import com.flowcortex.user.api.UserService;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Default context: {@code flowcortex.spaces.enabled} is not set, and absent means off. The session
 * space is refused with 503 and writes nothing, while the activation listener still provisions.
 */
@Testcontainers
@SpringBootTest
class SpaceSwitchedOffIT extends AbstractPostgresBootTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private SessionSpaces sessionSpaces;

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void anAbsentPropertyMeansTheSessionSpaceAnswers503AndNothingIsWritten() {
        UUID account = UUID.randomUUID();

        assertThatThrownBy(() -> sessionSpaces.sessionSpace(account))
                .isInstanceOfSatisfying(LocalizedStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getDetailMessageCode()).isEqualTo("feature.turnedOff");
                });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM space WHERE owner_id = ?", Long.class, account)).isZero();
    }

    @Test
    void activationStillProvisionsThePersonalSpaceWithTheSwitchOff() {
        UUID owner = userService.createInitialAdmin("space-off-" + UUID.randomUUID() + "@example.com", "password123",
                "Switch Off").id();

        Instant deadline = Instant.now().plus(TIMEOUT);
        BooleanSupplier provisioned = () -> jdbc.queryForObject(
                "SELECT count(*) FROM space_member m JOIN space s ON s.id = m.space_id "
                        + "WHERE s.owner_id = ? AND s.is_default AND m.user_id = ? AND m.role = 'manager'",
                Long.class, owner, owner) == 1;
        while (!provisioned.getAsBoolean()) {
            assertThat(Instant.now()).as("personal space provisioned within %s", TIMEOUT).isBefore(deadline);
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting", e);
            }
        }
    }
}
