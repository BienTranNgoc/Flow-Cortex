package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.MediaAsset;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, UUID> {

    List<MediaAsset> findByRunId(UUID runId);

    List<MediaAsset> findByRunStepId(UUID runStepId);

    List<MediaAsset> findByRunIdAndAssetType(UUID runId, String assetType);
}
