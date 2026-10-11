package com.flowcortex.app.capabilities;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flowcortex.media.api.MediaCapability;
import com.flowcortex.media.application.MediaProperties;
import com.flowcortex.media.application.MediaProperties.S3;
import com.flowcortex.media.application.MediaProperties.Upload;
import com.flowcortex.app.capabilities.CapabilitiesControllerTest.MediaFlagOverride;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc(addFilters = false)
@Import(ProductVideoEnabledTest.MediaFlagOverride.class)
@WebMvcTest(CapabilitiesController.class)
@TestPropertySource(properties = "flowcortex.product-video.enabled=true")
class ProductVideoEnabledTest {

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
    void productVideoFlagFlipsOnWhenPropertyIsTrue() throws Exception {
        mockMvc.perform(get("/api/v1/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mediaEnabled").value(true))
                .andExpect(jsonPath("$.productVideoEnabled").value(true));
    }

    /** Reference to the parent test's {@link MediaFlagOverride} would link {@code MediaProperties}
     * compilation here too — Spring Boot picks one bean when both classes contribute. */
    @SuppressWarnings("unused")
    private static final Class<?> PARENT_OVERRIDE = MediaFlagOverride.class;
}
