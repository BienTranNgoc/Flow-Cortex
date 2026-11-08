package com.flowcortex.space.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.space.api.SpaceContext;
import com.flowcortex.space.api.SpaceRole;
import com.flowcortex.space.infrastructure.SpaceRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class SessionSpacesImplTest {

    private final SpaceRepository spaces = mock(SpaceRepository.class);
    private final UUID userId = UUID.randomUUID();

    @Test
    void withTheSwitchOffTheSessionSpaceIsRefusedWith503AndNothingIsRead() {
        SessionSpacesImpl sessionSpaces = new SessionSpacesImpl(new SpaceSwitch(false), spaces);

        assertThatThrownBy(() -> sessionSpaces.sessionSpace(userId))
                .isInstanceOfSatisfying(LocalizedStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getDetailMessageCode()).isEqualTo("feature.turnedOff");
                });
        verifyNoInteractions(spaces);
    }

    @Test
    void withTheSwitchOnTheRepositorysSpaceIsReturned() {
        SpaceContext personal = new SpaceContext(UUID.randomUUID(), SpaceRole.MANAGER);
        when(spaces.ensurePersonal(userId)).thenReturn(personal);

        assertThat(new SessionSpacesImpl(new SpaceSwitch(true), spaces).sessionSpace(userId)).isEqualTo(personal);
    }
}
