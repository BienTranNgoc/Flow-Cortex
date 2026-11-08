package com.flowcortex.space.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.space.api.SpaceAction;
import com.flowcortex.space.api.SpaceRole;
import org.junit.jupiter.api.Test;

class SpacePolicyTest {

    @Test
    void managerIsGrantedEveryAction() {
        for (SpaceAction action : SpaceAction.values()) {
            assertThat(SpacePolicy.grants(SpaceRole.MANAGER, action)).as(action.name()).isTrue();
        }
    }

    @Test
    void editorAndViewerAreGrantedNothing() {
        for (SpaceAction action : SpaceAction.values()) {
            assertThat(SpacePolicy.grants(SpaceRole.EDITOR, action)).as("editor " + action).isFalse();
            assertThat(SpacePolicy.grants(SpaceRole.VIEWER, action)).as("viewer " + action).isFalse();
        }
    }

    @Test
    void aNullRoleOrActionIsRefused() {
        assertThat(SpacePolicy.grants(null, SpaceAction.SPACE_READ)).isFalse();
        assertThat(SpacePolicy.grants(SpaceRole.MANAGER, null)).isFalse();
    }
}
