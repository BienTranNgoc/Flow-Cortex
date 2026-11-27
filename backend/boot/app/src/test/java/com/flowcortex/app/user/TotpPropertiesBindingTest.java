package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.totp.TotpProperties;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * The keyring rules of the shipped YAML files, bound the way the application binds them: the environment
 * fills {@code keys.k1} by relaxed binding, the dev profile carries a fixed key under the same id, and no
 * other profile may start without one.
 */
class TotpPropertiesBindingTest {

    private static final String ENV_KEY = "FLOWCORTEX_AUTH_TOTP_KEYS_K1";
    private static final String OPERATOR_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static TotpProperties bind(boolean devProfile, Map<String, Object> environment) throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addLast(new SystemEnvironmentPropertySource("systemEnvironment", environment));
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        if (devProfile) {
            loader.load("application-dev", new ClassPathResource("application-dev.yml")).forEach(sources::addLast);
        }
        loader.load("application", new ClassPathResource("application.yml")).forEach(sources::addLast);
        Binder binder = new Binder(ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources));
        return binder.bind("flowcortex.auth.totp", Bindable.of(TotpProperties.class)).get();
    }

    private static Set<String> violatedProperties(TotpProperties properties) {
        Set<ConstraintViolation<TotpProperties>> violations = VALIDATOR.validate(properties);
        return violations.stream().map(violation -> violation.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void devProfileAloneIsValid() throws IOException {
        TotpProperties properties = bind(true, Map.of());

        assertThat(properties.keys()).containsOnlyKeys("k1");
        assertThat(Base64.getDecoder().decode(properties.keys().get("k1"))).hasSize(32);
        assertThat(properties.currentKeyId()).isEqualTo("k1");
        assertThat(properties.issuer()).isEqualTo("Nodi (dev)");
        assertThat(properties.adminRequired()).isTrue();
        assertThat(properties.lockout()).isEqualTo(Duration.ofMinutes(15));
        assertThat(violatedProperties(properties)).isEmpty();
    }

    @Test
    void blankEnvKeyPlusDevKeyIsValid() throws IOException {
        TotpProperties properties = bind(true, Map.of(ENV_KEY, ""));

        assertThat(properties.keys()).containsOnlyKeys("k1");
        assertThat(properties.keys().get("k1")).isNotBlank();
        assertThat(violatedProperties(properties)).isEmpty();
    }

    @Test
    void envKeyReplacesTheDevKey() throws IOException {
        TotpProperties properties = bind(true, Map.of(ENV_KEY, OPERATOR_KEY));

        assertThat(properties.keys()).isEqualTo(Map.of("k1", OPERATOR_KEY));
    }

    @Test
    void envKeyAloneIsValidOutsideDev() throws IOException {
        TotpProperties properties = bind(false, Map.of(ENV_KEY, OPERATOR_KEY));

        assertThat(properties.keys()).isEqualTo(Map.of("k1", OPERATOR_KEY));
        assertThat(properties.issuer()).isEqualTo("Nodi");
        assertThat(violatedProperties(properties)).isEmpty();
    }

    @Test
    void noKeyOutsideDevFails() throws IOException {
        assertThat(violatedProperties(bind(false, Map.of()))).containsExactly("keys");
        assertThat(violatedProperties(bind(false, Map.of(ENV_KEY, "")))).containsExactly("keys");
    }
}
