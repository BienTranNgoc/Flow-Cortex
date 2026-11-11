package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.RunStepAudioPeaks;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Stored waveforms; the first writer of a key wins. */
public interface RunStepAudioPeaksRepository extends JpaRepository<RunStepAudioPeaks, RunStepAudioPeaks.Key> {

    /** Stores the peaks unless the key already holds a row; {@code 0} when another writer was first. */
    @Modifying
    @Transactional
    @Query(value = "INSERT INTO run_step_audio_peaks (run_step_id, attempt, output_name, peaks, computed_at) "
            + "VALUES (:runStepId, :attempt, :outputName, CAST(:peaks AS jsonb), :computedAt) "
            + "ON CONFLICT DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("runStepId") UUID runStepId, @Param("attempt") short attempt,
            @Param("outputName") String outputName, @Param("peaks") String peaks,
            @Param("computedAt") Instant computedAt);
}
