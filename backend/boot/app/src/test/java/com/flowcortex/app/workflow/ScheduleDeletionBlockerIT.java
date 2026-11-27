package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.AccountDeletionBlocker;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.infrastructure.WorkflowScheduleRepository;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The blocker reads the member's own active schedules through the real repository and schema. */
@SpringBootTest
class ScheduleDeletionBlockerIT extends AbstractWorkflowModuleIT {

    private static final String KEY = "workflow.active_schedule";
    private static final Pattern WELL_FORMED = Pattern.compile("^[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*$");

    @Autowired
    private List<AccountDeletionBlocker> blockers;

    @Autowired
    private WorkflowScheduleRepository schedules;

    @Test
    void anActiveScheduleBlocks() {
        UUID owner = newUser("blocker-active").id();
        schedule(owner, true);

        assertThat(blocker().blocks(owner)).isTrue();
    }

    @Test
    void aPausedScheduleDoesNotBlock() {
        UUID owner = newUser("blocker-paused").id();
        schedule(owner, false);

        assertThat(blocker().blocks(owner)).isFalse();
    }

    @Test
    void anotherAccountsScheduleDoesNotBlock() {
        schedule(newUser("blocker-other").id(), true);
        UUID bystander = newUser("blocker-bystander").id();

        assertThat(blocker().blocks(bystander)).isFalse();
    }

    @Test
    void keyIsWellFormedAndOrderIsThree() {
        AccountDeletionBlocker blocker = blocker();

        assertThat(blocker.key()).matches(WELL_FORMED);
        assertThat(blocker.defaultSortOrder()).isEqualTo(3);
        assertThat(blocker.defaultMessageEn()).isNotBlank();
        assertThat(blocker.defaultMessageVi()).isNotBlank();
        assertThat(blockers).extracting(AccountDeletionBlocker::key).doesNotHaveDuplicates();
    }

    private AccountDeletionBlocker blocker() {
        return blockers.stream().filter(candidate -> candidate.key().equals(KEY)).findFirst()
                .orElseThrow(() -> new AssertionError("no blocker keyed " + KEY));
    }

    private void schedule(UUID owner, boolean active) {
        UUID workflow = newWorkflow(owner, false, new NewWorkflowStep("only", "echo", null)).id();
        Instant now = Instant.now();
        schedules.upsert(UUID.randomUUID(), owner, workflow, "daily", null, null, LocalTime.of(8, 0),
                "Asia/Ho_Chi_Minh", active, active ? now.plusSeconds(3600) : null, null, false, now);
    }
}
