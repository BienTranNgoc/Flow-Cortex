package com.flowcortex.support.request;

import com.flowcortex.media.api.StoredAttachment;
import java.util.List;

/**
 * A purged request's images are to be handed back to the store's expiry. Published in the transaction that
 * deletes the request, so the registry keeps it until the release has gone through.
 */
public record SupportAttachmentsReleaseRequested(long requestNumber, List<StoredAttachment> attachments) {
}
