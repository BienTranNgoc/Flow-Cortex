package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.workflow.api.ProductVideoWorkflow;
import com.flowcortex.workflow.application.ForkEligibility.Refusal;
import com.flowcortex.workflow.domain.Workflow;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ForkEligibilityTest {

    private static final UUID CALLER = UUID.randomUUID();
    private static final UUID OTHER_OWNER = UUID.randomUUID();
    private static final UUID DOUYIN = UUID.fromString("f2c8d6ef-9d9c-4c8a-91b3-7c6a7d2fb101");

    @Test
    void theOwnerIsRefusedAsOwnEvenWhenForkingIsOff() {
        assertThat(ForkEligibility.refusal(UUID.randomUUID(), CALLER, false, CALLER)).contains(Refusal.OWN);
    }

    @Test
    void productVideoIsRefusedAsFixed() {
        assertThat(ForkEligibility.refusal(ProductVideoWorkflow.ID, Workflow.SYSTEM_OWNER_ID, true, CALLER))
                .contains(Refusal.FIXED);
    }

    @Test
    void aSourceWithForkingOffIsRefusedAsNotAllowed() {
        assertThat(ForkEligibility.refusal(UUID.randomUUID(), OTHER_OWNER, false, CALLER))
                .contains(Refusal.NOT_ALLOWED);
    }

    @Test
    void anotherMembersForkableWorkflowIsAllowed() {
        assertThat(ForkEligibility.refusal(UUID.randomUUID(), OTHER_OWNER, true, CALLER)).isEmpty();
    }

    @Test
    void douyinWithForkingOnIsAllowed() {
        assertThat(ForkEligibility.refusal(DOUYIN, Workflow.SYSTEM_OWNER_ID, true, CALLER)).isEmpty();
    }
}
