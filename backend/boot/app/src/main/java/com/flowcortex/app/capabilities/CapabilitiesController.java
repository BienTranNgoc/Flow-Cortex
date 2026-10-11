package com.flowcortex.app.capabilities;

import com.flowcortex.media.api.MediaJobs;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tells the SPA which opt-in flows are live, so the UI can hide entry points whose backend would
 * otherwise refuse the run. Lives in {@code :boot:app} (composition root) by Modulith's
 * module-boundary rules: it reads the media kill switch through the exported {@link MediaJobs}
 * port and the
 * {@code flowcortex.product-video.enabled} flag owned by {@code :modules:workflow}.
 *
 * <p>The same data also lives on the per-endpoint failure mode
 * ({@code ProductVideosController} returns 503 when the flag is off) — the endpoint is a hint, not
 * a contract; the backend remains the source of truth.
 */
@RestController
@RequestMapping("/api/v1")
public class CapabilitiesController {

    private final MediaJobs mediaJobs;
    private final boolean productVideoEnabled;

    public CapabilitiesController(
            MediaJobs mediaJobs,
            @org.springframework.beans.factory.annotation.Value(
                    "${flowcortex.product-video.enabled:false}") boolean productVideoEnabled) {
        this.mediaJobs = mediaJobs;
        this.productVideoEnabled = productVideoEnabled;
    }

    @GetMapping(value = "/capabilities", produces = MediaType.APPLICATION_JSON_VALUE)
    public CapabilitiesResponse capabilities() {
        return new CapabilitiesResponse(mediaJobs.isEnabled(), productVideoEnabled);
    }

    public record CapabilitiesResponse(boolean mediaEnabled, boolean productVideoEnabled) {
    }
}
