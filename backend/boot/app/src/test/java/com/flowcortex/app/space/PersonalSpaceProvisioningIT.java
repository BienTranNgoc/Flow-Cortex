package com.flowcortex.app.space;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.space.api.SessionSpaces;
import com.flowcortex.space.api.SpaceContext;
import com.flowcortex.space.api.SpaceRole;
import com.flowcortex.space.infrastructure.SpaceRepository;
import com.flowcortex.user.api.UserActivatedEvent;
import com.flowcortex.user.api.UserService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Personal-space provisioning with the switch on: the activation listener runs after the account
 * commits on another thread, and concurrent first requests are settled by the database's unique
 * index, not by the callers.
 */
@Testcontainers
@SpringBootTest(properties = "flowcortex.spaces.enabled=true")
class PersonalSpaceProvisioningIT extends AbstractPostgresBootTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int CALLERS = 8;

    @Autowired
    private SessionSpaces sessionSpaces;

    @Autowired
    private SpaceRepository spaceRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void anActivatedAccountGetsOneSpaceWithOneManagerMemberAndARedeliveryChangesNothing() {
        UUID owner = userService.createInitialAdmin("space-" + UUID.randomUUID() + "@example.com", "password123",
                "Space Owner").id();
        await(() -> spaceCount(owner) == 1);

        transactionTemplate.executeWithoutResult(status -> events.publishEvent(new UserActivatedEvent(owner)));
        await(() -> outstandingPublications(owner) == 0);

        assertThat(spaceCount(owner)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT m.user_id, m.role FROM space_member m JOIN space s ON s.id = m.space_id "
                + "WHERE s.owner_id = ?", owner)).singleElement().satisfies(member -> {
                    assertThat(member.get("user_id")).isEqualTo(owner);
                    assertThat(member.get("role")).isEqualTo("manager");
                });
    }

    @Test
    void theFirstSessionSpaceCreatesTheSpaceAndASecondCallReturnsTheSameOne() {
        UUID owner = UUID.randomUUID();

        SpaceContext first = sessionSpaces.sessionSpace(owner);
        SpaceContext second = sessionSpaces.sessionSpace(owner);

        assertThat(first.role()).isEqualTo(SpaceRole.MANAGER);
        assertThat(second).isEqualTo(first);
        assertThat(spaceCount(owner)).isEqualTo(1);
        assertThat(memberCount(owner)).isEqualTo(1);
    }

    @Test
    void concurrentFirstRequestsLeaveOneSpaceOneMemberAndOneReturnedId() throws Exception {
        UUID owner = UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(CALLERS);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(CALLERS);
        try {
            List<Future<SpaceContext>> results = new ArrayList<>();
            for (int i = 0; i < CALLERS; i++) {
                Callable<SpaceContext> call = () -> {
                    ready.countDown();
                    go.await();
                    return sessionSpaces.sessionSpace(owner);
                };
                results.add(pool.submit(call));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<UUID> ids = new ArrayList<>();
            for (Future<SpaceContext> result : results) {
                ids.add(result.get(10, TimeUnit.SECONDS).spaceId());
            }
            assertThat(ids).hasSize(CALLERS).containsOnly(ids.get(0));
        } finally {
            pool.shutdownNow();
        }

        assertThat(spaceCount(owner)).isEqualTo(1);
        assertThat(memberCount(owner)).isEqualTo(1);
    }

    @Test
    void aPersonalSpaceWithoutItsManagerRowGetsTheMembershipInserted() {
        UUID owner = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        jdbc.update("INSERT INTO space (id, owner_id, is_default, created_at) VALUES (?, ?, true, ?)", spaceId, owner,
                Timestamp.from(Instant.now()));
        assertThat(memberCount(owner)).isZero();

        SpaceContext repaired = sessionSpaces.sessionSpace(owner);

        assertThat(repaired).isEqualTo(new SpaceContext(spaceId, SpaceRole.MANAGER));
        assertThat(memberCount(owner)).isEqualTo(1);
        assertThat(spaceCount(owner)).isEqualTo(1);
    }

    @Test
    void theOwnerIsManagerOfItsSpaceAndAnotherAccountHasNoRole() {
        UUID owner = UUID.randomUUID();
        UUID spaceId = sessionSpaces.sessionSpace(owner).spaceId();

        assertThat(spaceRepository.findMemberRole(spaceId, owner)).contains(SpaceRole.MANAGER);
        assertThat(spaceRepository.findMemberRole(spaceId, UUID.randomUUID())).isEmpty();
    }

    private long spaceCount(UUID owner) {
        return jdbc.queryForObject("SELECT count(*) FROM space WHERE owner_id = ? AND is_default", Long.class, owner);
    }

    private long memberCount(UUID owner) {
        return jdbc.queryForObject("SELECT count(*) FROM space_member WHERE user_id = ?", Long.class, owner);
    }

    private long outstandingPublications(UUID userId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM event_publication "
                + "WHERE event_type LIKE '%UserActivatedEvent%' AND serialized_event LIKE ? AND completion_date IS NULL",
                Long.class, "%" + userId + "%");
        return count == null ? 0 : count;
    }

    private static void await(BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Condition not met within " + TIMEOUT);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting", e);
            }
        }
    }
}
