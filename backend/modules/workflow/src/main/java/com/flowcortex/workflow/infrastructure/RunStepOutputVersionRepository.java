package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.RunStepOutputVersion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The stored edit history of run steps. The caller holds the step's row lock, so it is the only writer. */
public interface RunStepOutputVersionRepository extends JpaRepository<RunStepOutputVersion, UUID> {

    /** The two newest versions of the step, newest first: the current edit and the one before it. */
    List<RunStepOutputVersion> findTop2ByRunStepIdOrderByVersionDesc(UUID runStepId);

    Optional<RunStepOutputVersion> findByRunStepIdAndVersion(UUID runStepId, int version);

    long countByRunStepId(UUID runStepId);

    /** Deletes every version of the step but the {@code keep} newest; returns how many it deleted. */
    @Modifying
    @Query(value = "DELETE FROM run_step_output_version WHERE run_step_id = :runStepId AND version NOT IN ("
            + "SELECT version FROM run_step_output_version WHERE run_step_id = :runStepId "
            + "ORDER BY version DESC LIMIT :keep)", nativeQuery = true)
    int deleteOldestBeyond(@Param("runStepId") UUID runStepId, @Param("keep") int keep);
}
