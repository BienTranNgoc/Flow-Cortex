package com.flowcortex.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The stored waveform of one sound a step produced, per attempt. {@code peaks} is a JSON array of
 * numbers, or {@code null} when the sound cannot be decoded, so it is not decoded again.
 */
@Entity
@IdClass(RunStepAudioPeaks.Key.class)
@Table(name = "run_step_audio_peaks")
public class RunStepAudioPeaks {

    /** Composite primary key of {@link RunStepAudioPeaks}. */
    public static class Key implements Serializable {

        private UUID runStepId;
        private short attempt;
        private String outputName;

        /** Required by JPA. */
        protected Key() {
        }

        public Key(UUID runStepId, short attempt, String outputName) {
            this.runStepId = runStepId;
            this.attempt = attempt;
            this.outputName = outputName;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && attempt == key.attempt
                    && Objects.equals(runStepId, key.runStepId) && Objects.equals(outputName, key.outputName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(runStepId, attempt, outputName);
        }
    }

    @Id
    @Column(name = "run_step_id", nullable = false, updatable = false)
    private UUID runStepId;

    @Id
    @Column(name = "attempt", nullable = false, updatable = false)
    private short attempt;

    @Id
    @Column(name = "output_name", nullable = false, updatable = false)
    private String outputName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "peaks", updatable = false)
    private String peaks;

    @Column(name = "computed_at", nullable = false, updatable = false)
    private Instant computedAt;

    /** Required by JPA. */
    protected RunStepAudioPeaks() {
    }

    public RunStepAudioPeaks(UUID runStepId, short attempt, String outputName, String peaks, Instant computedAt) {
        this.runStepId = runStepId;
        this.attempt = attempt;
        this.outputName = outputName;
        this.peaks = peaks;
        this.computedAt = computedAt;
    }

    public UUID getRunStepId() {
        return runStepId;
    }

    public short getAttempt() {
        return attempt;
    }

    public String getOutputName() {
        return outputName;
    }

    /** The peaks JSON array, or {@code null} for a sound that cannot be decoded. */
    public String getPeaks() {
        return peaks;
    }

    public Instant getComputedAt() {
        return computedAt;
    }
}
