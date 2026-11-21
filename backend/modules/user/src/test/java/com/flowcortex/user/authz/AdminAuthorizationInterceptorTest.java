package com.flowcortex.user.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.security.authz.Public;
import com.flowcortex.shared.security.authz.RequirePermission;
import com.flowcortex.shared.web.AdminDisabledException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

class AdminAuthorizationInterceptorTest {

    @SuppressWarnings("unused")
    static class Handlers {
        void bare() {
        }

        @Public
        void open() {
        }

        @RequirePermission(Permission.SUPPORT_TICKET_VIEW)
        void tickets() {
        }
    }

    private final CallerFactsLoader loader = mock(CallerFactsLoader.class);
    private final AdminAuthorizationInterceptor interceptor = new AdminAuthorizationInterceptor(loader);
    private final UUID callerId = UUID.randomUUID();

    @BeforeEach
    void signIn() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject(callerId.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private boolean call(String handlerName, String pattern) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
        HandlerMethod handler = new HandlerMethod(new Handlers(), Handlers.class.getDeclaredMethod(handlerName));
        return interceptor.preHandle(request, new MockHttpServletResponse(), handler);
    }

    @Test
    void anUngovernedHandlerWithoutAPostureIsLeftAlone() throws Exception {
        assertThat(call("bare", "/api/v1/auth/me")).isTrue();
        verifyNoInteractions(loader);
    }

    @Test
    void aGovernedHandlerWithoutAPostureIsDenied() {
        assertThatThrownBy(() -> call("bare", "/api/v1/admin/runs")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> call("bare", "/api/v1/users/{id}")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aPathThatOnlySharesTheUsersPrefixIsNotGoverned() throws Exception {
        assertThat(call("bare", "/api/v1/usersettings")).isTrue();
    }

    @Test
    void aPublicHandlerReadsNoCaller() throws Exception {
        assertThat(call("open", "/api/v1/admin/ping")).isTrue();
        verifyNoInteractions(loader);
    }

    @Test
    void aKeyHandlerFollowsTheCallersFactsReadOnThisRequest() throws Exception {
        when(loader.load(callerId)).thenReturn(new CallerFacts(false, false, Set.of(Permission.SUPPORT_TICKET_VIEW)));
        assertThat(call("tickets", "/api/v1/admin/support/requests")).isTrue();

        when(loader.load(callerId)).thenReturn(new CallerFacts(false, false, Set.of()));
        assertThatThrownBy(() -> call("tickets", "/api/v1/admin/support/requests"))
                .isInstanceOf(AccessDeniedException.class);

        when(loader.load(callerId)).thenReturn(new CallerFacts(false, true, Set.of(Permission.SUPPORT_TICKET_VIEW)));
        assertThatThrownBy(() -> call("tickets", "/api/v1/admin/support/requests"))
                .isInstanceOf(AdminDisabledException.class);
    }

    @Test
    void aKeyHandlerWithoutASignedInCallerIsUnauthenticated() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> call("tickets", "/api/v1/admin/support/requests"))
                .isInstanceOf(InsufficientAuthenticationException.class);
        verifyNoInteractions(loader);
    }
}
