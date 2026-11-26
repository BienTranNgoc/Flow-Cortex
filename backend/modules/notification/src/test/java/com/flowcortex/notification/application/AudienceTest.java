package com.flowcortex.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.shared.web.LocalizedStatusException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AudienceTest {

    @Test
    void absentIsMember() {
        assertThat(Audience.parse(null)).isEqualTo(Audience.MEMBER);
    }

    @Test
    void memberAndAdminParse() {
        assertThat(Audience.parse("member")).isEqualTo(Audience.MEMBER);
        assertThat(Audience.parse("admin")).isEqualTo(Audience.ADMIN);
    }

    @Test
    void anotherValueIsRefusedNamingAudience() {
        for (String value : new String[] {"staff", "", "ADMIN", "Member", " admin"}) {
            assertThatThrownBy(() -> Audience.parse(value)).isInstanceOfSatisfying(LocalizedStatusException.class,
                    e -> {
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(e.getDetailMessageCode()).isEqualTo("notification.page.invalid");
                        assertThat(e.getDetailMessageArguments()).containsExactly(value, "audience");
                    });
        }
    }

    @Test
    void adminTypesStartWithAdminUnderscore() {
        assertThat(Audience.ADMIN.holds("admin_support_sla")).isTrue();
        assertThat(Audience.MEMBER.holds("admin_support_sla")).isFalse();
        assertThat(Audience.MEMBER.holds("support_reply")).isTrue();
        assertThat(Audience.ADMIN.holds("support_reply")).isFalse();
        assertThat(Audience.MEMBER.holds("administrator_x")).isTrue();
        assertThat(Audience.ADMIN.holds("administrator_x")).isFalse();
    }

    @Test
    void ofTypeFollowsThePrefix() {
        assertThat(Audience.of("admin_support_sla")).isEqualTo(Audience.ADMIN);
        assertThat(Audience.of("support_reply")).isEqualTo(Audience.MEMBER);
        assertThat(Audience.of("administrator_x")).isEqualTo(Audience.MEMBER);
    }

    @Test
    void theSqlClauseEscapesTheUnderscore() {
        assertThat(Audience.ADMIN.sqlClause()).isEqualTo("type LIKE 'admin\\_%'");
        assertThat(Audience.MEMBER.sqlClause()).isEqualTo("type NOT LIKE 'admin\\_%'");
    }
}
