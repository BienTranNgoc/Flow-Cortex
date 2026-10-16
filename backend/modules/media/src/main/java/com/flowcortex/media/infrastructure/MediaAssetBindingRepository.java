package com.flowcortex.media.infrastructure;

import com.flowcortex.media.domain.MediaAssetBinding;
import com.flowcortex.media.domain.MediaAssetBindingId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaAssetBindingRepository extends JpaRepository<MediaAssetBinding, MediaAssetBindingId> {

    List<MediaAssetBinding> findByRunIdOrderByPositionAsc(UUID runId);
}
