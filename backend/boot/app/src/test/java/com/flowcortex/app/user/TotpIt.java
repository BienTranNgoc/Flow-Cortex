package com.flowcortex.app.user;

import com.flowcortex.user.application.AccountCredentials;
import com.flowcortex.user.application.GoogleOAuthClient;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The one application context every TOTP IT shares: Google sign-in configured and its client spied (only the
 * code exchange is stubbed), the account-credentials port spied (only the reset failure case stubs it), personal
 * access tokens and the MCP authorization server on, one movable clock and one mutable console requirement,
 * both reset by {@link AbstractTotpIT} before each case. A property of its own would build another context.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.security.oauth.google.client-id=test-client.apps.googleusercontent.com",
        "flowcortex.security.oauth.google.client-secret=test-secret",
        "flowcortex.security.oauth.google.redirect-uri=http://localhost:5173/api/v1/auth/oauth/google/callback",
        "flowcortex.auth.personal-access-tokens.enabled=true",
        "flowcortex.auth.mcp-oauth.enabled=true",
        "flowcortex.app.public-url=https://app.nodi.vn/"})
@AutoConfigureTestRestTemplate
@Import(TotpIt.Config.class)
@MockitoSpyBean(types = {GoogleOAuthClient.class, AccountCredentials.class})
public @interface TotpIt {

    @TestConfiguration(proxyBeanMethods = false)
    class Config {

        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock();
        }

        @Bean
        @Primary
        TestTotpRequirement testTotpRequirement() {
            return new TestTotpRequirement();
        }
    }
}
