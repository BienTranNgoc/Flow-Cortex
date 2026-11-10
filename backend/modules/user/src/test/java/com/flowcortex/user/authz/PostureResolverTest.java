package com.flowcortex.user.authz;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.shared.security.authz.AuthenticatedOnly;
import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.security.authz.Public;
import com.flowcortex.shared.security.authz.RequireAnyPermission;
import com.flowcortex.shared.security.authz.RequirePermission;
import java.lang.reflect.Method;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PostureResolverTest {

    @SuppressWarnings("unused")
    static class Handlers {
        void none() {
        }

        @RequirePermission({Permission.USER_ACCOUNT_VIEW, Permission.USER_ACCOUNT_UPDATE})
        void allOf() {
        }

        @RequireAnyPermission(Permission.SUPPORT_TICKET_VIEW)
        void anyOf() {
        }

        @RequirePermission({})
        void emptyKeys() {
        }

        @Public
        void open() {
        }

        @AuthenticatedOnly
        void signedIn() {
        }

        @Public
        @AuthenticatedOnly
        void twoPostures() {
        }

        @RequirePermission(Permission.USER_ACCOUNT_VIEW)
        @RequireAnyPermission(Permission.SUPPORT_TICKET_VIEW)
        void keyAndAny() {
        }
    }

    private static Posture of(String name) throws NoSuchMethodException {
        Method method = Handlers.class.getDeclaredMethod(name);
        return PostureResolver.of(method);
    }

    @Test
    void readsEachOfTheFourPostures() throws Exception {
        assertThat(of("none")).isInstanceOf(Posture.None.class);
        assertThat(of("allOf")).isEqualTo(
                new Posture.AllOf(Set.of(Permission.USER_ACCOUNT_VIEW, Permission.USER_ACCOUNT_UPDATE)));
        assertThat(of("anyOf")).isEqualTo(new Posture.AnyOf(Set.of(Permission.SUPPORT_TICKET_VIEW)));
        assertThat(of("open")).isInstanceOf(Posture.Public.class);
        assertThat(of("signedIn")).isInstanceOf(Posture.AuthenticatedOnly.class);
    }

    @Test
    void twoPosturesOnOneHandlerConflict() throws Exception {
        assertThat(of("twoPostures")).isInstanceOf(Posture.Conflict.class);
        assertThat(of("keyAndAny")).isInstanceOf(Posture.Conflict.class);
    }

    @Test
    void aKeyPostureNamingNoKeyConflicts() throws Exception {
        assertThat(of("emptyKeys")).isInstanceOf(Posture.Conflict.class);
    }
}
