package com.flowcortex.asset.application;

import com.flowcortex.asset.api.SpaceAssetSummary;
import com.flowcortex.asset.api.SpaceAssets;
import com.flowcortex.asset.infrastructure.SpaceAssetStore;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class SpaceAssetsImpl implements SpaceAssets {

    private final SpaceAssetStore assets;

    SpaceAssetsImpl(SpaceAssetStore assets) {
        this.assets = assets;
    }

    @Override
    public List<SpaceAssetSummary> findInSpace(UUID spaceId, Collection<UUID> assetIds) {
        return assets.findInSpace(spaceId, assetIds);
    }
}
