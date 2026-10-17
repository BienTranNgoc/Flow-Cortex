package com.flowcortex.media.domain;

import java.io.Serializable;
import java.util.UUID;

public record MediaAssetBindingId(UUID runId, String bindingKey) implements Serializable {
}
