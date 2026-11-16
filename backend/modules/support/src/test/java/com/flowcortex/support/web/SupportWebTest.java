package com.flowcortex.support.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.support.config.AssistantSwitch;
import com.flowcortex.support.config.SupportProperties;
import com.flowcortex.user.api.CurrentUser;
import com.flowcortex.user.api.UserDirectory;
import jakarta.servlet.FilterChain;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.LocaleResolver;

class SupportWebTest {

    private final HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
    private final FilterChain chain = mock(FilterChain.class);

    @Test
    void theHelpCenterSwitchOffRefusesEverySupportRoute() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/support/kb/groups");

        filter(false, true).doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(refusalCode()).isEqualTo("support-disabled");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void theAssistantSwitchOffRefusesOnlyQuestions() throws Exception {
        for (String path : List.of("/api/v1/support/assistant/messages",
                "/api/v1/support/assistant/guest/messages")) {
            HandlerExceptionResolver pathResolver = mock(HandlerExceptionResolver.class);
            new SupportSwitchFilter(properties(true, false, null), pathResolver)
                    .doFilter(new MockHttpServletRequest("POST", path), new MockHttpServletResponse(), chain);
            ArgumentCaptor<Exception> refusal = ArgumentCaptor.forClass(Exception.class);
            verify(pathResolver).resolveException(any(), any(), eq(null), refusal.capture());
            assertThat(((SupportProblemException) refusal.getValue()).code()).isEqualTo("assistant-disabled");
        }

        for (String path : List.of("/api/v1/support/kb/groups", "/api/v1/support/assistant/conversations/latest",
                "/api/v1/support/assistant/guest/conversations/latest")) {
            MockHttpServletRequest read = new MockHttpServletRequest("GET", path);
            filter(true, false).doFilter(read, new MockHttpServletResponse(), chain);
            verify(chain).doFilter(eq(read), any());
        }
        verify(resolver, never()).resolveException(any(), any(), any(), any());
    }

    @Test
    void adminRoutesPassWithTheHelpCenterOff() throws Exception {
        MockHttpServletRequest admin = new MockHttpServletRequest("GET", "/api/v1/admin/support/kb/articles");

        filter(false, false).doFilter(admin, new MockHttpServletResponse(), chain);

        verify(chain).doFilter(eq(admin), any());
        verify(resolver, never()).resolveException(any(), any(), any(), any());
    }

    @Test
    void aRefusalRendersAsItsProblemType() {
        ProblemDetail detail = handler(Locale.ENGLISH).problem(
                new SupportProblemException(HttpStatus.CONFLICT, "group-not-empty",
                        new LocalizedMessage("support.kb.groupNotEmpty", 2L)).with("articleCount", 2L),
                new MockHttpServletRequest());

        assertThat(detail.getDetail()).isEqualTo("The topic group still holds 2 article(s)");
        assertThat(detail.getStatus()).isEqualTo(409);
        assertThat(detail.getType()).isEqualTo(URI.create("https://flow-cortex.dev/problems/group-not-empty"));
        assertThat(detail.getProperties()).containsEntry("articleCount", 2L);
        assertThat(detail.getProperties()).containsEntry("code", "group-not-empty");
    }

    @Test
    void theCallerIsReadFromTheRowAndAMissingRowIsNotFound() {
        UserDirectory directory = mock(UserDirectory.class);
        SupportCaller caller = new SupportCaller(directory);
        UUID memberId = UUID.randomUUID();
        when(directory.findById(memberId)).thenReturn(Optional.of(user(memberId, "MEMBER")));

        assertThat(caller.require(jwt(memberId)).id()).isEqualTo(memberId);
        assertThatThrownBy(() -> caller.require(jwt(UUID.randomUUID()))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void theSettingsNeedBothAssistantSwitchesAndHideAnEmptyContact() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(Boolean.class))).thenReturn(List.of(true), List.of(false), List.of());

        SupportSettingsController.SettingsResponse on = settings(properties(true, true, " "), jdbc);
        SupportSettingsController.SettingsResponse consoleOff = settings(properties(true, true, "hotro@nodi.vn"), jdbc);
        SupportSettingsController.SettingsResponse rowMissing = settings(properties(true, true, null), jdbc);

        assertThat(on.contactEmail()).isNull();
        assertThat(on.assistantAvailable()).isTrue();
        assertThat(consoleOff.contactEmail()).isEqualTo("hotro@nodi.vn");
        assertThat(consoleOff.assistantAvailable()).isFalse();
        assertThat(rowMissing.assistantAvailable()).isFalse();
        assertThat(settings(properties(true, false, null), jdbc).assistantAvailable()).isFalse();
    }

    private static SupportSettingsController.SettingsResponse settings(SupportProperties properties, JdbcTemplate jdbc) {
        return new SupportSettingsController(properties, new AssistantSwitch(properties, jdbc)).settings();
    }

    private SupportSwitchFilter filter(boolean enabled, boolean assistantEnabled) {
        return new SupportSwitchFilter(properties(enabled, assistantEnabled, null), resolver);
    }

    @Test
    void theDetailAndEveryFieldErrorFollowTheRequestLanguage() {
        SupportProblemException refusal = new SupportProblemException(HttpStatus.BAD_REQUEST, "validation-failed",
                new LocalizedMessage("problem.validationFailed"))
                .withFieldErrors(Map.of("subject", new LocalizedMessage("support.request.error.subject")));

        ProblemDetail vietnamese = handler(Locale.of("vi")).problem(refusal, new MockHttpServletRequest());
        ProblemDetail english = handler(Locale.ENGLISH).problem(refusal, new MockHttpServletRequest());

        assertThat(vietnamese.getProperties()).containsEntry("errors", Map.of("subject", "Tiêu đề cần 5–120 ký tự"));
        assertThat(english.getProperties())
                .containsEntry("errors", Map.of("subject", "The subject needs 5–120 characters"));
        assertThat(vietnamese.getDetail()).startsWith("Yêu cầu chưa hợp lệ");
        assertThat(english.getDetail()).isEqualTo("Request validation failed");
    }

    private static SupportProblemHandler handler(Locale locale) {
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("i18n/messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        LocaleResolver localeResolver = mock(LocaleResolver.class);
        when(localeResolver.resolveLocale(any())).thenReturn(locale);
        return new SupportProblemHandler(messages, localeResolver);
    }

    private String refusalCode() {
        ArgumentCaptor<Exception> refusal = ArgumentCaptor.forClass(Exception.class);
        verify(resolver).resolveException(any(), any(), eq(null), refusal.capture());
        SupportProblemException problem = (SupportProblemException) refusal.getValue();
        assertThat(problem.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        return problem.code();
    }

    private static SupportProperties properties(boolean enabled, boolean assistantEnabled, String contact) {
        return new SupportProperties(enabled, contact,
                new SupportProperties.Assistant(assistantEnabled, "gemini-3.5-flash-lite", 2000, 50, 10, 100));
    }

    private static CurrentUser user(UUID id, String role) {
        return new CurrentUser(id, id, "u@example.com", "U", role, "ACTIVE", true, false);
    }

    private static Jwt jwt(UUID subject) {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(subject.toString());
        return jwt;
    }
}
