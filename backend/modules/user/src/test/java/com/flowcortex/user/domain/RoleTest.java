package com.flowcortex.user.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class RoleTest {

    @Test
    void ofTheSameCodeTwiceIsTheSameInstance() {
        assertThat(Role.of("HO_TRO_CAP_2")).isSameAs(Role.of("HO_TRO_CAP_2"));
    }

    @Test
    void theSystemConstantsAreTheInternedInstancesOfTheirCodes() {
        assertThat(Role.of("ADMIN")).isSameAs(Role.ADMIN);
        assertThat(Role.of("MEMBER")).isSameAs(Role.MEMBER);
        assertThat(Role.of("PLATFORM_OPERATOR")).isSameAs(Role.PLATFORM_OPERATOR);
    }

    @Test
    void onlyAdminIsSuperAdmin() {
        assertThat(Role.ADMIN.isSuperAdmin()).isTrue();
        assertThat(Stream.of(Role.MEMBER, Role.CREATOR, Role.OPERATION_MANAGER, Role.CUSTOMER_SUPPORT,
                Role.CONTENT_EDITOR, Role.PLATFORM_OPERATOR, Role.of("CUSTOM"))
                .noneMatch(Role::isSuperAdmin)).isTrue();
    }

    @Test
    void equalityAndTextFollowTheCode() {
        assertThat(Role.of("CUSTOM")).isEqualTo(Role.of("CUSTOM")).hasSameHashCodeAs(Role.of("CUSTOM"));
        assertThat(Role.of("CUSTOM")).isNotEqualTo(Role.of("OTHER"));
        assertThat(Role.of("CUSTOM").code()).isEqualTo("CUSTOM");
        assertThat(Role.of("CUSTOM")).hasToString("CUSTOM");
    }
}
