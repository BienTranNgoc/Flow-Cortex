package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.workflow.application.ServedDefinition.Facts;
import com.flowcortex.workflow.application.ServedDefinition.Live;
import com.flowcortex.workflow.application.ServedDefinition.Pinned;
import com.flowcortex.workflow.application.ServedDefinition.Pointer;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ServedDefinitionTest {

    private static final UUID POINTER = UUID.randomUUID();

    @Test
    void aNonOwnersPinWinsOverThePointer() {
        assertThat(ServedDefinition.resolve(new Facts(false, false, 2, POINTER))).isEqualTo(new Pinned(2));
    }

    @Test
    void theOwnerIsServedLiveEvenWithAStrayPin() {
        assertThat(ServedDefinition.resolve(new Facts(false, true, 2, POINTER))).isEqualTo(new Live());
    }

    @Test
    void theOwnerWithoutAPinIsServedLive() {
        assertThat(ServedDefinition.resolve(new Facts(false, true, null, POINTER))).isEqualTo(new Live());
    }

    @Test
    void aNonOwnerWithoutAPinIsServedThePointer() {
        assertThat(ServedDefinition.resolve(new Facts(false, false, null, POINTER))).isEqualTo(new Pointer(POINTER));
    }

    @Test
    void aNonOwnerWithNoPointerIsServedLive() {
        assertThat(ServedDefinition.resolve(new Facts(false, false, null, null))).isEqualTo(new Live());
    }

    @Test
    void aSystemWorkflowIsServedLiveEvenWithAStrayPointer() {
        assertThat(ServedDefinition.resolve(new Facts(true, false, 3, POINTER))).isEqualTo(new Live());
    }
}
