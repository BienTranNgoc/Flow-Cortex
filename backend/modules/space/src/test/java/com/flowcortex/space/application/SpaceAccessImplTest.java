package com.flowcortex.space.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowcortex.space.api.SpaceAction;
import com.flowcortex.space.api.SpaceRole;
import com.flowcortex.space.infrastructure.SpaceRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SpaceAccessImplTest {

    private final SpaceRepository spaces = mock(SpaceRepository.class);
    private final SpaceAccessImpl access = new SpaceAccessImpl(spaces);
    private final UUID userId = UUID.randomUUID();
    private final UUID spaceId = UUID.randomUUID();

    @Test
    void aManagerMayReadAndAssertCanReturns() {
        when(spaces.findMemberRole(spaceId, userId)).thenReturn(Optional.of(SpaceRole.MANAGER));

        assertThat(access.can(userId, SpaceAction.SPACE_READ, spaceId)).isTrue();
        assertThatCode(() -> access.assertCan(userId, SpaceAction.SPACE_READ, spaceId,
                () -> new IllegalStateException("must not be built"))).doesNotThrowAnyException();
    }

    @Test
    void aNonMemberIsRefusedAndAssertCanThrowsTheSuppliersOwnInstance() {
        when(spaces.findMemberRole(spaceId, userId)).thenReturn(Optional.empty());
        RuntimeException supplied = new IllegalStateException("not found");

        assertThat(access.can(userId, SpaceAction.SPACE_READ, spaceId)).isFalse();
        assertThatThrownBy(() -> access.assertCan(userId, SpaceAction.SPACE_READ, spaceId, () -> supplied))
                .isSameAs(supplied);
    }

    @Test
    void anUnknownSpaceIdIsRefusedWithTheSameInstanceAsAForeignOne() {
        UUID unknown = UUID.randomUUID();
        when(spaces.findMemberRole(unknown, userId)).thenReturn(Optional.empty());
        when(spaces.findMemberRole(spaceId, userId)).thenReturn(Optional.empty());
        RuntimeException supplied = new IllegalStateException("not found");

        assertThatThrownBy(() -> access.assertCan(userId, SpaceAction.SPACE_READ, unknown, () -> supplied)).isSameAs(supplied);
        assertThatThrownBy(() -> access.assertCan(userId, SpaceAction.SPACE_READ, spaceId, () -> supplied)).isSameAs(supplied);
    }

    @Test
    void aRoleThePolicyGrantsNothingIsRefused() {
        when(spaces.findMemberRole(spaceId, userId)).thenReturn(Optional.of(SpaceRole.VIEWER));
        RuntimeException supplied = new IllegalStateException("not found");

        assertThat(access.can(userId, SpaceAction.SPACE_READ, spaceId)).isFalse();
        assertThatThrownBy(() -> access.assertCan(userId, SpaceAction.SPACE_READ, spaceId, () -> supplied))
                .isSameAs(supplied);
    }

    @Test
    void aNullArgumentIsRefusedWithoutReadingMembership() {
        RuntimeException supplied = new IllegalStateException("not found");

        assertThat(access.can(null, SpaceAction.SPACE_READ, spaceId)).isFalse();
        assertThat(access.can(userId, null, spaceId)).isFalse();
        assertThat(access.can(userId, SpaceAction.SPACE_READ, null)).isFalse();
        assertThatThrownBy(() -> access.assertCan(null, SpaceAction.SPACE_READ, spaceId, () -> supplied)).isSameAs(supplied);
        verifyNoInteractions(spaces);
    }
}
