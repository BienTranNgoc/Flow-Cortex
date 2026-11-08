package com.flowcortex.space.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.space.api.SpaceContext;
import com.flowcortex.space.api.SpaceRole;
import com.flowcortex.space.infrastructure.SpaceRepository;
import com.flowcortex.user.api.UserActivatedEvent;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PersonalSpaceProvisionerTest {

    private final SpaceRepository spaces = mock(SpaceRepository.class);
    private final PersonalSpaceProvisioner provisioner = new PersonalSpaceProvisioner(spaces);
    private final UUID userId = UUID.randomUUID();

    @Test
    void anActivatedAccountGetsItsPersonalSpaceEnsuredOnce() {
        when(spaces.ensurePersonal(userId)).thenReturn(new SpaceContext(UUID.randomUUID(), SpaceRole.MANAGER));

        provisioner.on(new UserActivatedEvent(userId));

        verify(spaces).ensurePersonal(userId);
    }

    @Test
    void provisioningDoesNotDependOnTheSpacesSwitchSoItStillEnsuresWithTheSwitchOff() {
        assertThat(PersonalSpaceProvisioner.class.getDeclaredConstructors()[0].getParameterTypes())
                .containsExactly(SpaceRepository.class);
        when(spaces.ensurePersonal(userId)).thenReturn(new SpaceContext(UUID.randomUUID(), SpaceRole.MANAGER));

        provisioner.on(new UserActivatedEvent(userId));

        verify(spaces).ensurePersonal(userId);
    }

    @Test
    void aRepositoryFailureIsNotSwallowedSoTheEventStaysIncomplete() {
        IllegalStateException failure = new IllegalStateException("database down");
        when(spaces.ensurePersonal(userId)).thenThrow(failure);

        assertThatThrownBy(() -> provisioner.on(new UserActivatedEvent(userId))).isSameAs(failure);
    }
}
