package com.flowcortex.connector.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class ConnectorEncryptionKeyTest {

    private static String key(int bytes) {
        byte[] key = new byte[bytes];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @Test
    void blankCurrentIdNamesTheSetting() {
        assertThatThrownBy(() -> ConnectorEncryptionConfig.buildCipher(" ", Map.of("k1", key(32))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FLOWCORTEX_CONNECTORS_ENCRYPTION_CURRENT");
        assertThatThrownBy(() -> ConnectorEncryptionConfig.buildCipher(null, Map.of("k1", key(32))))
                .hasMessageContaining("FLOWCORTEX_CONNECTORS_ENCRYPTION_CURRENT");
    }

    @Test
    void emptyKeyringNamesTheSetting() {
        assertThatThrownBy(() -> ConnectorEncryptionConfig.buildCipher("k1", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FLOWCORTEX_CONNECTORS_ENCRYPTION_KEYS_");
        assertThatThrownBy(() -> ConnectorEncryptionConfig.buildCipher("k1", null))
                .hasMessageContaining("FLOWCORTEX_CONNECTORS_ENCRYPTION_KEYS_");
    }

    @Test
    void currentIdNamingNoKeyNamesTheCurrentSetting() {
        assertThatThrownBy(() -> ConnectorEncryptionConfig.buildCipher("k2", Map.of("k1", key(32))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FLOWCORTEX_CONNECTORS_ENCRYPTION_CURRENT")
                .hasMessageContaining("k2");
    }

    @Test
    void upperCaseCurrentIdIsRefusedWithALowerCaseHint() {
        assertThatThrownBy(() -> ConnectorEncryptionConfig.buildCipher("K1", Map.of("k1", key(32))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FLOWCORTEX_CONNECTORS_ENCRYPTION_CURRENT 'K1'")
                .hasMessageContaining("[a-z0-9]{1,16}")
                .hasMessageContaining("lower-case");
    }

    @Test
    void blankNonCurrentKeyNamesItsId() {
        assertThatThrownBy(() -> ConnectorEncryptionConfig.buildCipher("k1", Map.of("k1", key(32), "k0", " ")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FLOWCORTEX_CONNECTORS_ENCRYPTION_KEYS_K0");
    }

    @Test
    void idOutsideThePatternIsRefused() {
        for (String id : new String[] {"K1", "a.b", "k_1", "a234567890123456x"}) {
            assertThatThrownBy(() -> ConnectorEncryptionConfig.buildCipher(id, Map.of(id, key(32))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("[a-z0-9]{1,16}");
        }
    }

    @Test
    void keyThatIsNotBase64IsRefusedWithoutEchoingIt() {
        String bad = "change-me-not-base64!!";
        assertThatThrownBy(() -> ConnectorEncryptionConfig.buildCipher("k1", Map.of("k1", bad)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base64")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(bad));
    }

    @Test
    void keyOfTheWrongLengthSaysThirtyTwoBytes() {
        for (int length : new int[] {16, 33}) {
            String bad = key(length);
            assertThatThrownBy(() -> ConnectorEncryptionConfig.buildCipher("k1", Map.of("k1", bad)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("32 bytes")
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain(bad));
        }
    }

    @Test
    void validKeyringBuilds() {
        assertThat(ConnectorEncryptionConfig.buildCipher("k1", Map.of("k1", key(32), "k2", key(32)))).isNotNull();
    }

    @Test
    void environmentVariablesBindToTheKeyringWithoutAYmlDeclaration() {
        String key = key(32);
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new SystemEnvironmentPropertySource("systemEnvironment", Map.of(
                "FLOWCORTEX_CONNECTORS_ENCRYPTION_CURRENT", "k1",
                "FLOWCORTEX_CONNECTORS_ENCRYPTION_KEYS_K1", key)));
        ConnectorEncryptionProperties bound = new Binder(ConfigurationPropertySources.from(sources))
                .bind("flowcortex.connectors.encryption", ConnectorEncryptionProperties.class).get();
        assertThat(bound.current()).isEqualTo("k1");
        assertThat(bound.keys()).containsExactly(Map.entry("k1", key));
    }

    @Test
    void environmentValueOverridesAPropertySourceKey() {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new SystemEnvironmentPropertySource("systemEnvironment", Map.of(
                "FLOWCORTEX_CONNECTORS_ENCRYPTION_KEYS_K1", key(16))));
        sources.addLast(new MapPropertySource("dev", Map.of(
                "flowcortex.connectors.encryption.current", "k1",
                "flowcortex.connectors.encryption.keys.k1", key(32))));
        ConnectorEncryptionProperties bound = new Binder(ConfigurationPropertySources.from(sources))
                .bind("flowcortex.connectors.encryption", ConnectorEncryptionProperties.class).get();
        assertThatThrownBy(() -> ConnectorEncryptionConfig.buildCipher(bound.current(), bound.keys()))
                .hasMessageContaining("32 bytes");
    }
}
