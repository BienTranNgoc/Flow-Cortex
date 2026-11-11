package com.flowcortex.user.pat;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.shared.ratelimit.InMemoryRateLimitStore;
import com.flowcortex.shared.ratelimit.RateLimitDefinition;
import com.flowcortex.shared.ratelimit.RateLimitKeys;
import com.flowcortex.shared.ratelimit.RateLimitStore;
import com.flowcortex.shared.ratelimit.RateLimitTelemetry;
import com.flowcortex.shared.ratelimit.RateLimiters;
import com.flowcortex.user.application.OpaqueTokens;
import com.flowcortex.user.application.RateLimit;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.ErrorResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

class PatRateLimitFilterTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-11T10:00:00Z"), ZoneOffset.UTC);
    private static final String TOKEN_A = "fc_pat_" + OpaqueTokens.generateRawToken();
    private static final String TOKEN_B = "fc_pat_" + OpaqueTokens.generateRawToken();

    private final List<String> keys = new ArrayList<>();
    private final InMemoryRateLimitStore memory = new InMemoryRateLimitStore(CLOCK);

    /** Records every key the limiters hand to the store. */
    private final RateLimitStore store = new RateLimitStore() {
        @Override
        public Optional<Duration> tryConsume(String key, long capacity, Duration period) {
            keys.add(key);
            return memory.tryConsume(key, capacity, period);
        }

        @Override
        public Optional<Duration> estimate(String key, long capacity, Duration period) {
            return memory.estimate(key, capacity, period);
        }

        @Override
        public void consume(String key, long capacity, Duration period) {
            memory.consume(key, capacity, period);
        }
    };

    private final HandlerExceptionResolver resolver = (request, response, handler, ex) -> {
        ErrorResponse error = (ErrorResponse) ex;
        response.setStatus(error.getStatusCode().value());
        error.getHeaders().forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
        return new ModelAndView();
    };

    private static PatRateLimitProperties properties(int perMinute, int perDay) {
        return new PatRateLimitProperties(new RateLimit(perMinute, Duration.ofMinutes(1)),
                new RateLimit(perDay, Duration.ofDays(1)));
    }

    private PatRateLimitFilter filter(int perMinute, int perDay) {
        PatRateLimitProperties properties = properties(perMinute, perDay);
        PatRateLimits limits = new PatRateLimits();
        RateLimiters limiters = new RateLimiters(
                List.of(limits.patTokenMinute(properties), limits.patTokenDay(properties)), store,
                new RateLimitTelemetry(new SimpleMeterRegistry(), CLOCK));
        return new PatRateLimitFilter(limiters, resolver);
    }

    private MockHttpServletResponse call(PatRateLimitFilter filter, String authorization) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/workflows");
        if (authorization != null) {
            request.addHeader(HttpHeaders.AUTHORIZATION, authorization);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void theSixtyFirstRequestInAMinuteAnswers429WithRetryAfter() throws Exception {
        PatRateLimitFilter filter = filter(60, 1000);

        for (int i = 1; i <= 60; i++) {
            assertThat(call(filter, "Bearer " + TOKEN_A).getStatus()).as("request %d", i).isEqualTo(200);
        }
        MockHttpServletResponse refused = call(filter, "Bearer " + TOKEN_A);

        assertThat(refused.getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(refused.getHeader(HttpHeaders.RETRY_AFTER))).isPositive();
    }

    @Test
    void theDayBudgetRefusesWhenTheMinuteBudgetHasRoom() throws Exception {
        PatRateLimitFilter filter = filter(5000, 1000);
        for (int i = 1; i <= 1000; i++) {
            assertThat(call(filter, "Bearer " + TOKEN_A).getStatus()).as("request %d", i).isEqualTo(200);
        }

        assertThat(call(filter, "Bearer " + TOKEN_A).getStatus()).isEqualTo(429);
    }

    @Test
    void anotherTokenKeepsItsOwnBudget() throws Exception {
        PatRateLimitFilter filter = filter(1, 1000);
        call(filter, "Bearer " + TOKEN_A);

        assertThat(call(filter, "Bearer " + TOKEN_A).getStatus()).isEqualTo(429);
        assertThat(call(filter, "Bearer " + TOKEN_B).getStatus()).isEqualTo(200);
    }

    @Test
    void requestsWithoutAPatBearerNeverReachALimiter() throws Exception {
        PatRateLimitFilter filter = filter(1, 1);

        assertThat(call(filter, "Bearer eyJhbGciOiJIUzI1NiJ9.e30.sig").getStatus()).isEqualTo(200);
        assertThat(call(filter, "Basic Zm9vOmJhcg==").getStatus()).isEqualTo(200);
        assertThat(call(filter, null).getStatus()).isEqualTo(200);

        assertThat(keys).isEmpty();
    }

    @Test
    void theLimiterIsKeyedOnTheTokenHashNeverTheRawToken() throws Exception {
        call(filter(60, 1000), "Bearer " + TOKEN_A);
        PatRateLimitProperties properties = properties(60, 1000);
        PatRateLimits limits = new PatRateLimits();

        assertThat(keys).containsExactly(
                RateLimitKeys.storageKey(limits.patTokenMinute(properties), OpaqueTokens.hash(TOKEN_A)),
                RateLimitKeys.storageKey(limits.patTokenDay(properties), OpaqueTokens.hash(TOKEN_A)));
    }

    @Test
    void declaresTheTwoLimitersWithTheirDefaults() {
        PatRateLimitProperties defaults = properties(60, 1000);
        PatRateLimits limits = new PatRateLimits();

        RateLimitDefinition minute = limits.patTokenMinute(defaults);
        RateLimitDefinition day = limits.patTokenDay(defaults);

        assertThat(minute.name()).isEqualTo("pat-token-minute");
        assertThat(minute.capacity()).isEqualTo(60);
        assertThat(minute.period()).isEqualTo(Duration.ofMinutes(1));
        assertThat(day.name()).isEqualTo("pat-token-day");
        assertThat(day.capacity()).isEqualTo(1000);
        assertThat(day.period()).isEqualTo(Duration.ofDays(1));
    }
}
