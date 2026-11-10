package com.flowcortex.workflow.api;

import java.util.List;

/** The waveform of a sound: 120 values in 0..1, or {@code null} when the server cannot decode it. */
public record PeaksView(List<Double> peaks) {
}
