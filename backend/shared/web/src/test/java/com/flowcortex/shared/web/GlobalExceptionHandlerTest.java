package com.flowcortex.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.HierarchicalMessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.LocaleResolver;

/**
 * Authored (and run red, with {@link GlobalExceptionHandler} temporarily
 * removed) before the advice was wired.
 */
@WebMvcTest(ThrowawayTestController.class)
class GlobalExceptionHandlerTest {

    static final String THING_NOT_FOUND = "test.thingNotFound";
    static final String THING_CONFLICTS = "test.thingConflicts";
    static final String THING_INCOMPLETE = "test.thingIncomplete";
    static final String THING_MISSING = "test.thingMissing";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LocaleResolver localeResolver;

    /** Test-only codes sit in a parent source: a test bundle named {@code i18n/messages} would shadow the real one. */
    @TestConfiguration
    static class TestOnlyMessages {

        @Bean
        static BeanPostProcessor testOnlyMessageParent() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if ("messageSource".equals(beanName) && bean instanceof HierarchicalMessageSource source) {
                        StaticMessageSource parent = new StaticMessageSource();
                        parent.addMessage(THING_NOT_FOUND, Locale.ENGLISH, "thing not found");
                        parent.addMessage(THING_CONFLICTS, Locale.ENGLISH, "thing conflicts");
                        parent.addMessage(THING_INCOMPLETE, Locale.ENGLISH, "thing is incomplete");
                        parent.addMessage(THING_MISSING, Locale.ENGLISH, "is missing");
                        source.setParentMessageSource(parent);
                    }
                    return bean;
                }
            };
        }
    }

    @Test
    void notFoundReturnsProblemDetail() throws Exception {
        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.title").exists())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("thing not found"));
    }

    @Test
    void conflictReturnsProblemDetail() throws Exception {
        mockMvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("thing conflicts"));
    }

    @Test
    void unprocessableReturns422WithItsCodeAndFieldErrors() throws Exception {
        mockMvc.perform(get("/test/unprocessable"))
                .andExpect(status().is(422))
                .andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.detail").value("thing is incomplete"))
                .andExpect(jsonPath("$.code").value("THING_INCOMPLETE"))
                .andExpect(jsonPath("$.errors['steps[1].config']").value("is missing"));
    }

    @Test
    void aLostOptimisticLockReturns409() throws Exception {
        mockMvc.perform(get("/test/optimistic-lock"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.type").value("https://flow-cortex.dev/problems/conflict"));
    }

    @Test
    void validationViolationReturns400NamingTheField() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.errors.name").exists());
    }

    @Test
    void anInvalidRequestParameterReturns400NamingTheParameter() throws Exception {
        mockMvc.perform(get("/test/page").param("size", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.errors.size").exists());
    }

    @Test
    void aRequestParameterOfTheWrongTypeReturns400NamingTheParameter() throws Exception {
        mockMvc.perform(get("/test/page").param("size", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.errors.size").exists());
    }

    @Test
    void aValidationFailureIsWrittenInVietnamese() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "vi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://flow-cortex.dev/problems/validation-failed"))
                .andExpect(jsonPath("$.detail").value("Yêu cầu chưa hợp lệ. Hãy kiểm tra lại các trường được đánh dấu."))
                .andExpect(jsonPath("$.errors.name").value("không được để trống"));
    }

    @Test
    void aParameterFailureIsWrittenInVietnamese() throws Exception {
        mockMvc.perform(get("/test/page").header(HttpHeaders.ACCEPT_LANGUAGE, "vi").param("size", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Yêu cầu chưa hợp lệ. Hãy kiểm tra lại các trường được đánh dấu."))
                .andExpect(jsonPath("$.errors.size").value("phải nhỏ hơn hoặc bằng 100"));
        mockMvc.perform(get("/test/page").header(HttpHeaders.ACCEPT_LANGUAGE, "vi").param("size", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.size").value("không đúng kiểu dữ liệu"));
    }

    @Test
    void aLocalizedStatusExceptionKeepsItsHeadersAndSpeaksTheRegionalVariant() throws Exception {
        mockMvc.perform(get("/test/rate-limited").header(HttpHeaders.ACCEPT_LANGUAGE, "vi-VN"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "30"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.detail").value("Bạn đã đăng nhập sai quá nhiều lần. Hãy thử lại sau."));
    }

    @Test
    void anUnexpectedFailureIsWrittenInVietnameseWithoutTheExceptionText() throws Exception {
        mockMvc.perform(get("/test/unexpected").header(HttpHeaders.ACCEPT_LANGUAGE, "vi"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("https://flow-cortex.dev/problems/internal-error"))
                .andExpect(jsonPath("$.detail").value("Đã xảy ra lỗi không mong muốn. Hãy thử lại sau."))
                .andExpect(content().string(not(containsString("hunter2"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"fr-FR", "*"})
    void anUnsupportedLanguageFallsBackToEnglish(String acceptLanguage) throws Exception {
        mockMvc.perform(get("/test/localized-not-found").header(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://flow-cortex.dev/problems/not-found"))
                .andExpect(jsonPath("$.detail").value("Workflow not found: x"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "@@@"})
    void aHeaderNamingNoLanguageIsEnglishEvenOnAVietnameseJvm(String acceptLanguage) {
        Locale jvmDefault = Locale.getDefault();
        Locale.setDefault(Locale.of("vi"));
        try {
            MockHttpServletRequest request = new MockHttpServletRequest() {
                // Tomcat answers getLocale() with the JVM default when the header names no language.
                @Override
                public Locale getLocale() {
                    return Locale.getDefault();
                }
            };
            request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage);

            assertThat(localeResolver.resolveLocale(request)).isEqualTo(Locale.ENGLISH);
        } finally {
            Locale.setDefault(jvmDefault);
        }
    }

    @ParameterizedTest
    @MethodSource("fixedSentences")
    void aFixedSentenceIsVietnameseForViAndTodaysEnglishWithoutAHeader(String path, int status, String english,
            String vietnamese) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.detail").value(english));
        mockMvc.perform(get(path).header(HttpHeaders.ACCEPT_LANGUAGE, "vi"))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.detail").value(vietnamese));
    }

    static Stream<Arguments> fixedSentences() {
        return Stream.of(
                Arguments.of("/test/unauthenticated", 401, "Authentication is required",
                        "Bạn cần đăng nhập để tiếp tục."),
                Arguments.of("/test/forbidden", 403, "You do not have permission to perform this action",
                        "Bạn không có quyền thực hiện thao tác này."),
                Arguments.of("/test/optimistic-lock", 409,
                        "The resource was changed by another request; reload it and try again",
                        "Dữ liệu vừa được một yêu cầu khác thay đổi. Hãy tải lại trang rồi thử lại."),
                Arguments.of("/test/locked", 423, "Account is locked until 2026-01-01T10:15:30Z",
                        "Tài khoản đang tạm khóa đến 2026-01-01T10:15:30Z. Hãy đăng nhập lại sau thời điểm đó."));
    }

    @Test
    void aLockedAccountKeepsItsUnlockTime() throws Exception {
        mockMvc.perform(get("/test/locked").header(HttpHeaders.ACCEPT_LANGUAGE, "vi"))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.type").value("https://flow-cortex.dev/problems/account-locked"))
                .andExpect(jsonPath("$.lockedUntil").value("2026-01-01T10:15:30Z"));
    }

    @Test
    void aConstraintViolationIsWrittenInVietnamese() throws Exception {
        mockMvc.perform(get("/test/constraint-violation").header(HttpHeaders.ACCEPT_LANGUAGE, "vi"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://flow-cortex.dev/problems/validation-failed"))
                .andExpect(jsonPath("$.detail").value("Yêu cầu chưa hợp lệ. Hãy kiểm tra lại các trường được đánh dấu."))
                .andExpect(jsonPath("$.errors.name").value("không được để trống"));
    }

    @Test
    void withoutAnAcceptLanguageTheFieldErrorIsTodaysText() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Request validation failed"))
                .andExpect(jsonPath("$.errors.name").value("must not be blank"));
    }

    @Test
    void withoutAnAcceptLanguageEveryConstraintTypeKeepsTodaysText() throws Exception {
        mockMvc.perform(post("/test/constraints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"present":null,"items":[],"text":"","email":"nope","ratio":"1:1",
                                 "accepted":false,"cap":101,"floor":0,"count":0}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.present").value("must not be null"))
                .andExpect(jsonPath("$.errors.items").value("must not be empty"))
                .andExpect(jsonPath("$.errors.text").value("size must be between 1 and 10000"))
                .andExpect(jsonPath("$.errors.email").value("must be a well-formed email address"))
                .andExpect(jsonPath("$.errors.ratio").value("must match \"9:16\""))
                .andExpect(jsonPath("$.errors.accepted").value("must be true"))
                .andExpect(jsonPath("$.errors.cap").value("must be less than or equal to 100"))
                .andExpect(jsonPath("$.errors.floor").value("must be greater than or equal to 1"))
                .andExpect(jsonPath("$.errors.count").value("must be greater than 0"));
    }
}
