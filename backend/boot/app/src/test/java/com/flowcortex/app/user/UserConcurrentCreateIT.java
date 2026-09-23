package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Concurrency: two parallel creates of the same (globally unique) email must leave exactly one
 * row, one caller succeeding and the other rejected — enforced by the DB's {@code UNIQUE
 * (email)} constraint, not an application-level check-then-insert (which would race).
 *
 * <p>Exercises {@link UserService} directly rather than HTTP: the race is a DB-level concern
 * below the web layer; two real HTTP threads would add noise without proving more — {@link
 * ConflictException} -> 409 is already proven by {@code UsersCrudEndpointsIT}.
 */
@Testcontainers
@SpringBootTest
class UserConcurrentCreateIT extends AbstractPostgresBootTest {

    @Autowired
    private UserService userService;

    private record Outcome(boolean success, UUID ownerId) {
    }

    @Test
    void twoParallelCreatesOfTheSameEmailExactlyOneSucceeds() throws Exception {
        UserView adminA = userService.createInitialAdmin(
                "race-admin-a-" + UUID.randomUUID() + "@example.com", "password123", "Race Admin A");
        UserView adminB = userService.createInitialAdmin(
                "race-admin-b-" + UUID.randomUUID() + "@example.com", "password123", "Race Admin B");
        String racedEmail = "raced-" + UUID.randomUUID() + "@example.com";

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Outcome> attempt = ownerAndReturn(ready, go, adminA.id(), racedEmail);
            Callable<Outcome> attemptOther = ownerAndReturn(ready, go, adminB.id(), racedEmail);

            Future<Outcome> futureA = pool.submit(attempt);
            Future<Outcome> futureB = pool.submit(attemptOther);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            Outcome outcomeA = futureA.get(10, TimeUnit.SECONDS);
            Outcome outcomeB = futureB.get(10, TimeUnit.SECONDS);

            assertThat(outcomeA.success() ^ outcomeB.success())
                    .as("exactly one of the two parallel creates must succeed")
                    .isTrue();

            Outcome winner = outcomeA.success() ? outcomeA : outcomeB;
            Outcome loser = outcomeA.success() ? outcomeB : outcomeA;

            // Each owner scope also lists the seed admin's own (self-owned)
            // row, so `contains` rather than `containsExactly`.
            List<UserView> winnerScope = userService.listUsers(winner.ownerId());
            assertThat(winnerScope).extracting(UserView::email).contains(racedEmail);

            List<UserView> loserScope = userService.listUsers(loser.ownerId());
            assertThat(loserScope).extracting(UserView::email).doesNotContain(racedEmail);
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<Outcome> ownerAndReturn(CountDownLatch ready, CountDownLatch go, UUID ownerId, String email) {
        return () -> {
            ready.countDown();
            go.await();
            try {
                userService.createUser(ownerId, email, "password123", "Racer", "MEMBER");
                return new Outcome(true, ownerId);
            } catch (ConflictException e) {
                return new Outcome(false, ownerId);
            }
        };
    }
}
