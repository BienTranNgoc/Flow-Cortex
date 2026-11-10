package com.flowcortex.notification.preferences;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PreferencePatchTest {

    @Test
    void anEmptyBodyIsRefusedAsBody() {
        assertThat(PreferencePatch.parse(Map.of()).errorField()).isEqualTo("body");
        assertThat(PreferencePatch.parse(null).errorField()).isEqualTo("body");
    }

    @Test
    void anUnknownFieldIsRefusedByName() {
        assertThat(PreferencePatch.parse(Map.of("digest", false)).errorField()).isEqualTo("digest");
    }

    @Test
    void nullOrNonBooleanIsRefusedByName() {
        Map<String, Object> nullValue = new LinkedHashMap<>();
        nullValue.put("inApp", null);

        assertThat(PreferencePatch.parse(nullValue).errorField()).isEqualTo("inApp");
        assertThat(PreferencePatch.parse(Map.of("careEmail", "no")).errorField()).isEqualTo("careEmail");
        assertThat(PreferencePatch.parse(Map.of("marketingEmail", 0)).errorField()).isEqualTo("marketingEmail");
    }

    @Test
    void anyValidSubsetIsAccepted() {
        PreferencePatch.Parsed one = PreferencePatch.parse(Map.of("marketingEmail", false));
        PreferencePatch.Parsed all = PreferencePatch.parse(
                Map.of("inApp", false, "careEmail", true, "marketingEmail", false));

        assertThat(one.errorField()).isNull();
        assertThat(one.patch()).isEqualTo(new PreferencePatch(null, null, false));
        assertThat(all.patch()).isEqualTo(new PreferencePatch(false, true, false));
    }

    @Test
    void theFirstErrorWinsAndNothingIsReturnedAsPatch() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("inApp", false);
        body.put("digest", false);
        body.put("careEmail", "no");

        PreferencePatch.Parsed parsed = PreferencePatch.parse(body);

        assertThat(parsed.errorField()).isEqualTo("digest");
        assertThat(parsed.patch()).isNull();
    }
}
