package com.flowcortex.connector.application.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BaseUrlPinTest {

    private static final String NOTION = "https://api.notion.com/v1";

    @Test
    void normalizeDropsTrailingSlashesAndLowerCasesTheHost() {
        assertThat(BaseUrlPin.normalize("https://API.Notion.com/v1/")).contains("https://api.notion.com/v1");
        assertThat(BaseUrlPin.normalize("  https://api.example.com//  ")).contains("https://api.example.com");
        assertThat(BaseUrlPin.normalize("https://api.example.com:443/x")).contains("https://api.example.com/x");
        assertThat(BaseUrlPin.normalize("https://api.example.com:8443/x"))
                .contains("https://api.example.com:8443/x");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://api.example.com",
            "https://169.254.169.254/latest",
            "https://[::1]/x",
            "https://localhost/x",
            "https://minio/bucket",
            "https://backend:8081/api",
            "https://user:pw@api.example.com",
            "https://api.example.com?x=1",
            "https://api.example.com#frag",
            "https://api.example.com/v1/../x",
            "https://api.example.com/v1/%2e%2e/x",
            "/relative/path",
            "",
            "not a url"})
    void normalizeRefusesWhatIsNotAPlainPublicOrigin(String raw) {
        assertThat(BaseUrlPin.normalize(raw)).isEmpty();
    }

    @Test
    void normalizeRefusesNull() {
        assertThat(BaseUrlPin.normalize(null)).isEmpty();
    }

    @Test
    void aLookAlikeHostIsAcceptedAtSaveBecauseTheProxyDecides() {
        assertThat(BaseUrlPin.normalize("https://127.attacker.example")).isNotEmpty();
    }

    @Test
    void aUrlUnderTheBasePathIsAllowedWhateverTheHostCase() {
        assertThat(BaseUrlPin.allows(NOTION, "https://API.notion.com/v1/pages?x=1")).isTrue();
        assertThat(BaseUrlPin.allows(NOTION, "https://api.notion.com/v1")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://api.notion.com.evil.com/v1/pages",
            "https://api.notion.com@evil.com/v1/pages",
            "https://evil.com@api.notion.com/v1/pages",
            "https://.evil.com/v1/pages",
            "https://api.notion.com/v10/pages",
            "https://api.notion.com/v1/../admin",
            "https://api.notion.com/v1/%2e%2e/admin",
            "https://api.notion.com/v1/%2E%2E/admin",
            "https://api.notion.com/v1/./pages",
            "https://api.notion.com:8443/v1/pages",
            "http://api.notion.com/v1/pages",
            "https://api.notion.com/other",
            "/v1/pages",
            "https://api.notion.com/v1/ pages",
            ""})
    void aTargetOutsideTheBaseIsRefused(String target) {
        assertThat(BaseUrlPin.allows(NOTION, target)).isFalse();
    }

    @Test
    void anExplicitDefaultPortEqualsNone() {
        assertThat(BaseUrlPin.allows(NOTION, "https://api.notion.com:443/v1/pages")).isTrue();
    }

    @Test
    void aBaseWithAnEmptyPathAllowsAnyPathOnThatOrigin() {
        Optional<String> base = BaseUrlPin.normalize("https://h.example.com");
        assertThat(base).isPresent();
        assertThat(BaseUrlPin.allows(base.get(), "https://h.example.com/anything")).isTrue();
        assertThat(BaseUrlPin.allows(base.get(), "https://h.example.com")).isTrue();
        assertThat(BaseUrlPin.allows(base.get(), "https://other.example.com/anything")).isFalse();
    }

    @Test
    void aNullTargetIsRefused() {
        assertThat(BaseUrlPin.allows(NOTION, null)).isFalse();
    }
}
