package com.flowcortex.support.request;

import com.flowcortex.media.api.MediaJobs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Releases the images of a purged request once the delete is committed. A failure is rethrown so the event
 * registry keeps the publication and retries it; it does not go through {@code AttachmentService.release},
 * which swallows every failure and would leave the images kept with no row left to find them by.
 */
@Component
class SupportAttachmentsReleaser {

    private static final Logger LOG = LoggerFactory.getLogger(SupportAttachmentsReleaser.class);

    private final MediaJobs mediaJobs;

    SupportAttachmentsReleaser(MediaJobs mediaJobs) {
        this.mediaJobs = mediaJobs;
    }

    @ApplicationModuleListener(id = "support.attachments-release")
    void on(SupportAttachmentsReleaseRequested event) {
        try {
            mediaJobs.releaseAttachments(event.attachments());
        } catch (RuntimeException e) {
            LOG.warn("support.attachment outcome=release_failed request={} count={} error={}", event.requestNumber(),
                    event.attachments().size(), e.getClass().getSimpleName());
            throw e;
        }
        LOG.info("support.attachment outcome=released request={} count={}", event.requestNumber(),
                event.attachments().size());
    }
}
