package com.flowcortex.llm.catalog;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CatalogModelRepository extends JpaRepository<CatalogModel, CatalogModel.Key> {

    Page<CatalogModel> findByCapabilityOrderByModelKeyAsc(String capability, Pageable pageable);

    /** Model counts of every capability that has rows, in one query; a capability without rows is absent. */
    @Query("SELECT m.capability AS capability, COUNT(m) AS total, COUNT(CASE WHEN m.enabled = true THEN 1 END) AS enabled "
            + "FROM CatalogModel m GROUP BY m.capability")
    List<ModelCount> countByCapability();

    /**
     * Creates the row only if no row holds the (capability, model key) pair: {@code 1} when created, {@code 0}
     * when the pair is taken. One statement, so two concurrent creates give one winner and never overwrite
     * its row.
     */
    @Modifying
    @Query(value = "INSERT INTO model_catalog (capability, model_key, provider_key, vendor_label, maker_label, "
            + "mode, enabled, not_implemented, created_at, updated_at) "
            + "VALUES (:capability, :modelKey, :providerKey, :vendorLabel, :makerLabel, :mode, :enabled, "
            + ":notImplemented, :now, :now) "
            + "ON CONFLICT (capability, model_key) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("capability") String capability, @Param("modelKey") String modelKey,
            @Param("providerKey") String providerKey, @Param("vendorLabel") String vendorLabel,
            @Param("makerLabel") String makerLabel, @Param("mode") String mode,
            @Param("enabled") boolean enabled, @Param("notImplemented") boolean notImplemented,
            @Param("now") Instant now);

    /** One capability's row counts. */
    interface ModelCount {

        String getCapability();

        long getTotal();

        long getEnabled();
    }
}
