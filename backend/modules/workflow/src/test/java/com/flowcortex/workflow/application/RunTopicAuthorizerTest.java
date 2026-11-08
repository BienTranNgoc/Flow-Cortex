package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RunTopicAuthorizerTest {

    private final WorkflowRunRepository runs = mock(WorkflowRunRepository.class);
    private final RunTopicAuthorizer authorizer = new RunTopicAuthorizer(runs);
    private final UUID runId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();

    @Test
    void claimsOnlyRunTopicsWithAUuid() {
        assertThat(authorizer.supports("run:" + runId)).isTrue();
        assertThat(authorizer.supports("run:not-a-uuid")).isFalse();
        assertThat(authorizer.supports("run:")).isFalse();
        assertThat(authorizer.supports("run:" + runId.toString().toUpperCase())).isFalse();
        assertThat(authorizer.supports("run:1-1-1-1-1")).isFalse();
        assertThat(authorizer.supports("chat:" + runId)).isFalse();
    }

    @Test
    void theOwnerMayReadTheTopic() {
        when(runs.findByIdAndOwnerId(runId, ownerId)).thenReturn(Optional.of(
                new WorkflowRun(runId, UUID.randomUUID(), ownerId, "in", Instant.now())));

        assertThat(authorizer.authorize(ownerId, "run:" + runId)).isTrue();
    }

    @Test
    void anotherMemberOrAMissingRunIsRefused() {
        when(runs.findByIdAndOwnerId(runId, ownerId)).thenReturn(Optional.empty());

        assertThat(authorizer.authorize(ownerId, "run:" + runId)).isFalse();
        assertThat(authorizer.authorize(UUID.randomUUID(), "run:" + UUID.randomUUID())).isFalse();
    }
}
