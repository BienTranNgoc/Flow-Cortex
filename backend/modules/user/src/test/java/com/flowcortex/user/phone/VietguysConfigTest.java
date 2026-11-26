package com.flowcortex.user.phone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.flowcortex.jobs.api.ClusterJob;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class VietguysConfigTest {

    private static final String SEED = "seed-value-that-must-not-print";
    private static final String USERNAME = "account-name-that-must-not-print";
    private static final String BRANDNAME = "BrandThatMustNotPrint";
    private static final String KEY = Base64.getEncoder().encodeToString(patternedKey());

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(VietguysConfig.class)
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void aSelectedCarrierWithoutItsCurrentKeyFailsNamingThatKeyAndNoValue() {
        runner.withPropertyValues(complete("flowcortex.sms.vietguys.keys.k1="))
                .run(context -> {
                    assertThat(context).hasFailed();
                    String messages = messagesOf(context.getStartupFailure());
                    assertThat(messages).contains("flowcortex.sms.vietguys.keys.k1");
                    assertThat(messages).doesNotContain(SEED, USERNAME, BRANDNAME, KEY);
                });
    }

    @Test
    void aSpareKeyLeftEmptyIsNotAKeyAndDoesNotFailStartup() {
        runner.withPropertyValues(complete("flowcortex.sms.vietguys.keys.k1=" + KEY,
                        "flowcortex.sms.vietguys.keys.k2="))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void aMissingRequiredValueFailsNamingItsPropertyAndNoValue() {
        runner.withPropertyValues(complete("flowcortex.sms.vietguys.keys.k1=" + KEY,
                        "flowcortex.sms.vietguys.brandname="))
                .run(context -> {
                    assertThat(context).hasFailed();
                    String messages = messagesOf(context.getStartupFailure());
                    assertThat(messages).contains("flowcortex.sms.vietguys.brandname");
                    assertThat(messages).doesNotContain(SEED, USERNAME, KEY);
                });
    }

    @Test
    void aTokenUrlWithoutAHostFailsNamingItsProperty() {
        runner.withPropertyValues(complete("flowcortex.sms.vietguys.keys.k1=" + KEY,
                        "flowcortex.sms.vietguys.token-url=not-a-url"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(messagesOf(context.getStartupFailure())).contains("flowcortex.sms.vietguys.token-url");
                });
    }

    @Test
    void aCompleteSetupBuildsTheSenderTheKeepaliveAndTheCredentialStatus() {
        runner.withPropertyValues(complete("flowcortex.sms.vietguys.keys.k1=" + KEY))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(VietguysSmsSender.class);
                    assertThat(context).hasSingleBean(CarrierCredentialStatus.class);
                    assertThat(context.getBeansOfType(ClusterJob.class).values())
                            .singleElement()
                            .satisfies(job -> {
                                assertThat(job.name()).isEqualTo("user.vietguys-token-keepalive");
                                assertThat(job.interval()).isEqualTo(Duration.ofHours(6));
                            });
                });
    }

    @Test
    void theOutboxSenderBuildsNoVietguysBean() {
        runner.withPropertyValues("flowcortex.sms.sender=outbox")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(VietguysSmsSender.class);
                    assertThat(context).doesNotHaveBean(VietguysTokenStore.class);
                    assertThat(context).doesNotHaveBean(CarrierCredentialStatus.class);
                    assertThat(context).doesNotHaveBean(ClusterJob.class);
                });
    }

    /** Every required value set, then {@code overrides} (later entries win on the same key). */
    private static String[] complete(String... overrides) {
        List<String> values = new ArrayList<>(List.of(
                "flowcortex.sms.sender=vietguys",
                "flowcortex.sms.vietguys.sms-url=https://sms.example.test/api",
                "flowcortex.sms.vietguys.token-url=https://token.example.test/refresh",
                "flowcortex.sms.vietguys.username=" + USERNAME,
                "flowcortex.sms.vietguys.brandname=" + BRANDNAME,
                "flowcortex.sms.vietguys.refresh-token=" + SEED,
                "flowcortex.sms.vietguys.current-key-id=k1",
                "flowcortex.sms.vietguys.templates.otp=Ma OTP {CODE}",
                "flowcortex.sms.vietguys.templates.phone-changed=So dien thoai cua ban da doi",
                "flowcortex.sms.vietguys.templates.phone-change-reverted=So dien thoai cua ban da duoc khoi phuc",
                "flowcortex.sms.vietguys.connect-timeout=5s",
                "flowcortex.sms.vietguys.read-timeout=10s"));
        for (String override : overrides) {
            String key = override.substring(0, override.indexOf('=') + 1);
            values.removeIf(value -> value.startsWith(key));
            values.add(override);
        }
        return values.toArray(String[]::new);
    }

    private static String messagesOf(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.append(cause.getMessage()).append('\n');
        }
        return messages.toString();
    }

    private static byte[] patternedKey() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i * 7 + 1);
        }
        return key;
    }
}
