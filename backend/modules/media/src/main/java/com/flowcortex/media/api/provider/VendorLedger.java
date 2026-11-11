package com.flowcortex.media.api.provider;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.flowcortex.media.api.VendorTaskReport;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The {@code vendorLedger} block of a {@code generate_video} job: the request key and up to five
 * unconsumed vendor jobs the worker may attach to instead of submitting again.
 */
public record VendorLedger(
        @NotNull @Pattern(regexp = "[0-9a-f]{64}") String requestKey,
        @NotNull @Size(max = 5) List<@NotNull @Valid Candidate> candidates) {

    /** One ledger row offered to the worker; {@code handle} holds vendor job identifiers only. */
    public record Candidate(
            @NotNull UUID vendorTaskId,
            @NotBlank @Size(max = 32) String vendor,
            @NotBlank @Size(max = 200) String model,
            @NotNull @Pattern(regexp = "[0-9a-f]{64}") String fingerprint,
            @NotBlank @Size(max = 200) String vendorJobId,
            @NotNull @Size(max = VendorTaskReport.MAX_HANDLE_PROPERTIES) Map<String, Object> handle) {

        @AssertTrue(message = "handle must serialize to at most 2048 bytes")
        @JsonIgnore
        public boolean isHandleWithinLimit() {
            return VendorTaskReport.handleFits(handle);
        }
    }
}
