package com.flowcortex.app.capabilities;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flowcortex.media.api.MediaCapability;
import com.flowcortex.media.application.MediaProperties;
import com.flowcortex.media.application.MediaProperties.S3;
import com.flowcortex.media.application.MediaProperties.Upload;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc(addFilters = false)
@Import(CapabilitiesControllerTest.MediaFlagOverride.class)
@WebMvcTest(CapabilitiesController.class)
class CapabilitiesControllerTest {

    /** A real {@link MediaProperties} record keeps the test honest about which knobs can be off
     * in production — every nested field is validated on construction. */
    @TestConfiguration
    static class MediaFlagOverride {
        @Bean
        MediaProperties mediaProperties() {
            return new MediaProperties(
                    true,
                    Duration.ofMinutes(30),
                    Map.of(
                            MediaCapability.PROBE.value(), Duration.ofMinutes(5),
                            MediaCapability.EXTRACT_AUDIO.value(), Duration.ofMinutes(10),
                            MediaCapability.RENDER_VIDEO.value(), Duration.ofMinutes(5)),
                    Duration.ofMinutes(20),
                    Duration.ofMinutes(15),
                    new Upload(524_288_000L, List.of("jpg", "jpeg", "png", "webp"),
                            Duration.ofMinutes(15)),
                    new S3("http://s3:9000", "http://localhost:9000", "us-east-1", true,
                            "flowcortex-uploads", "flowcortex-media", "k", "s", Duration.ofSeconds(5),
                            Duration.ofSeconds(2)));
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void capabilitiesReflectsBothFlags() throws Exception {
        mockMvc.perform(get("/api/v1/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mediaEnabled").value(true))
                .andExpect(jsonPath("$.productVideoEnabled").value(false));
    }
}
