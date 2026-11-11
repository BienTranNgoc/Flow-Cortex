package com.flowcortex.workflow.application;

/** What decoding a sound came to: its peaks, a verdict that it cannot be decoded, or a read that failed. */
sealed interface PeaksOutcome {

    /** 120 values in 0..1. */
    record Peaks(float[] values) implements PeaksOutcome {
    }

    /** The bytes are not audio this server decodes; the verdict is final and is stored. */
    record NotDecodable() implements PeaksOutcome {
    }

    /** The sound's bytes could not be read to the end; nothing is stored, so a later request tries again. */
    record StoreUnavailable() implements PeaksOutcome {
    }
}
