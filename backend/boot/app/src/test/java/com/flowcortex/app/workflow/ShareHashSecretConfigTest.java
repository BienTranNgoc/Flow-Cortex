package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * What the shipped application files resolve for the share access-log hash secret, read from the main
 * resources alone (the integration-test properties under {@code config/} would otherwise mask the files).
 * The component refuses a blank or short value (see {@code AccessorKeysTest}), so a blank here is a refused start.
 */
class ShareHashSecretConfigTest {

    private static final String PROPERTY = "flowcortex.workflow.share.accessor-hash-secret";
    private static final int MIN_SECRET_BYTES = 32;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.config.location=classpath:/");

    @Test
    void theDevProfileResolvesAUsableSecretWithNothingSet() {
        runner.withPropertyValues("spring.profiles.active=dev").run(context ->
                assertThat(context.getEnvironment().getProperty(PROPERTY).getBytes(StandardCharsets.UTF_8))
                        .hasSizeGreaterThanOrEqualTo(MIN_SECRET_BYTES));
    }

    @Test
    void aSetEnvironmentValueOverridesTheDevFallback() {
        runner.withPropertyValues("spring.profiles.active=dev",
                        "FLOWCORTEX_WORKFLOW_SHARE_HASH_SECRET=from-the-environment-0123456789")
                .run(context -> assertThat(context.getEnvironment().getProperty(PROPERTY))
                        .isEqualTo("from-the-environment-0123456789"));
    }

    @Test
    void everyOtherProfileResolvesNoSecret() {
        runner.run(context -> assertThat(context.getEnvironment().getProperty(PROPERTY)).isEmpty());
        runner.withPropertyValues("spring.profiles.active=prod").run(context ->
                assertThat(context.getEnvironment().getProperty(PROPERTY)).isEmpty());
    }

    @Test
    void anExplicitlyEmptyValueStaysEmptyEvenOnTheDevProfile() {
        runner.withPropertyValues("spring.profiles.active=dev", "FLOWCORTEX_WORKFLOW_SHARE_HASH_SECRET=")
                .run(context -> assertThat(context.getEnvironment().getProperty(PROPERTY)).isEmpty());
    }
}
