package com.flowcortex.media.infrastructure;

import com.flowcortex.media.domain.MediaAsset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, UUID> {

    Optional<MediaAsset> findByIdAndOwnerId(UUID id, UUID ownerId);
}
