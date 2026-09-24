package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.ReviewTask;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewTaskRepository extends JpaRepository<ReviewTask, UUID> {

    List<ReviewTask> findByRunId(UUID runId);

    Optional<ReviewTask> findByRunStepId(UUID runStepId);

    List<ReviewTask> findByStatus(String status);
}
