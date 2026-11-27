package com.flowcortex.llm.catalog;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface CatalogCapabilityRepository extends JpaRepository<CatalogCapability, String> {

    List<CatalogCapability> findAllByOrderByCategoryAscCapabilityAsc();
}
