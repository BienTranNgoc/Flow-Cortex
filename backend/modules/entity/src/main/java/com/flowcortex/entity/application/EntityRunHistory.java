package com.flowcortex.entity.application;

import com.flowcortex.entity.infrastructure.EntityRunStore;
import com.flowcortex.entity.infrastructure.EntityRunStore.CompletedRun;
import com.flowcortex.entity.infrastructure.EntityStore;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.PageView;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The completed runs that used an entity of the caller's space. */
@Service
public class EntityRunHistory {

    private final EntityStore entities;
    private final EntityRunStore runs;

    public EntityRunHistory(EntityStore entities, EntityRunStore runs) {
        this.entities = entities;
        this.runs = runs;
    }

    /** @throws NotFoundException when the space holds no live entity with this id */
    @Transactional(readOnly = true)
    public PageView<CompletedRun> completedRuns(UUID spaceId, UUID entityId, int page, int size) {
        entities.findLive(entityId, spaceId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("entity.notFound")));
        return runs.pageCompleted(entityId, page, size);
    }
}
