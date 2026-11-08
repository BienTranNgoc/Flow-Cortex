package com.flowcortex.realtime.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.realtime.application.StreamRegistry.Slot;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StreamRegistryTest {

    private static final int CAP = 3;
    private static final UUID USER = UUID.randomUUID();

    private static List<Slot> admit(StreamRegistry registry, UUID user, int count) {
        List<Slot> slots = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            slots.add(registry.tryAdmit(user).orElseThrow());
        }
        return slots;
    }

    @Test
    void admitsUpToTheCapAndRefusesOneMore() {
        StreamRegistry registry = new StreamRegistry(CAP);

        admit(registry, USER, CAP - 1);
        assertThat(registry.tryAdmit(USER)).isPresent();
        assertThat(registry.tryAdmit(USER)).isEmpty();
    }

    @Test
    void aReleasedSlotAdmitsAgain() {
        StreamRegistry registry = new StreamRegistry(CAP);
        List<Slot> slots = admit(registry, USER, CAP);

        slots.get(0).release();

        assertThat(registry.tryAdmit(USER)).isPresent();
        assertThat(registry.tryAdmit(USER)).isEmpty();
    }

    @Test
    void releaseIsIdempotent() {
        StreamRegistry registry = new StreamRegistry(CAP);
        List<Slot> slots = admit(registry, USER, CAP);

        slots.get(0).release();
        slots.get(0).release();

        assertThat(registry.tryAdmit(USER)).isPresent();
        assertThat(registry.tryAdmit(USER)).isEmpty();
    }

    @Test
    void theCapIsPerUser() {
        StreamRegistry registry = new StreamRegistry(CAP);
        admit(registry, USER, CAP);

        assertThat(registry.tryAdmit(UUID.randomUUID())).isPresent();
    }

    @Test
    void theRegistryIsPerInstanceWithNoSharedState() {
        StreamRegistry instanceA = new StreamRegistry(CAP);
        StreamRegistry instanceB = new StreamRegistry(CAP);
        admit(instanceA, USER, CAP);

        assertThat(instanceA.tryAdmit(USER)).isEmpty();
        assertThat(instanceB.tryAdmit(USER)).isPresent();
    }
}
