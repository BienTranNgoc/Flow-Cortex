package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.ProductVideoInput;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductVideoInputRepository extends JpaRepository<ProductVideoInput, UUID> {
}
