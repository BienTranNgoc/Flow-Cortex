package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.MediaStoreUnavailableException;
import com.flowcortex.workflow.api.PeaksView;
import com.flowcortex.workflow.domain.RunStepAudioPeaks;
import com.flowcortex.workflow.infrastructure.RunStepAudioPeaksRepository;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Waveforms of stored sounds, computed once per step attempt and output name. Two decodes run at once per
 * instance; a request that cannot start one within the wait is told to come back, and a read that fails stores
 * nothing, so only a sound the server cannot decode is remembered as such.
 */
@Service
class AudioPeaksService {

    private static final Logger LOG = LoggerFactory.getLogger(AudioPeaksService.class);
    private static final int MAX_CONCURRENT_DECODES = 2;
    private static final Duration DECODE_WAIT = Duration.ofSeconds(10);
    private static final long MAX_SECONDS = 3600;

    /** Decodes the object {@code bucket}/{@code key}. */
    @FunctionalInterface
    interface Decoder {
        PeaksOutcome decode(String bucket, String key);
    }

    private final RunStepAudioPeaksRepository peaks;
    private final Decoder decoder;
    private final Duration wait;
    private final Semaphore slots = new Semaphore(MAX_CONCURRENT_DECODES, true);
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    AudioPeaksService(RunStepAudioPeaksRepository peaks, MediaJobs mediaJobs) {
        this(peaks, (bucket, key) -> decodeFromStore(mediaJobs, bucket, key), DECODE_WAIT);
    }

    AudioPeaksService(RunStepAudioPeaksRepository peaks, Decoder decoder, Duration wait) {
        this.peaks = peaks;
        this.decoder = decoder;
        this.wait = wait;
    }

    /**
     * The waveform of {@code name} of the step attempt, read from storage when it was computed before.
     * @throws PeaksRetryLaterException 503 when no decode slot frees up in time or the sound cannot be read
     */
    PeaksView peaks(UUID runStepId, short attempt, String name, String bucket, String objectKey) {
        RunStepAudioPeaks.Key id = new RunStepAudioPeaks.Key(runStepId, attempt, name);
        Optional<RunStepAudioPeaks> stored = peaks.findById(id);
        if (stored.isPresent()) {
            return view(stored.get().getPeaks());
        }
        acquire();
        try {
            // Another request may have stored it while this one waited for a slot.
            stored = peaks.findById(id);
            if (stored.isPresent()) {
                return view(stored.get().getPeaks());
            }
            String computed = switch (decoder.decode(bucket, objectKey)) {
                case PeaksOutcome.Peaks result -> toJson(result.values());
                case PeaksOutcome.NotDecodable ignored -> null;
                case PeaksOutcome.StoreUnavailable ignored -> {
                    LOG.warn("Waveform not stored, the sound could not be read: runStepId={} attempt={} name={}",
                            runStepId, attempt, name);
                    throw PeaksRetryLaterException.unavailable();
                }
            };
            peaks.insertIfAbsent(runStepId, attempt, name, computed, Instant.now());
            // The first writer wins: serve what is stored, so racing viewers see one result.
            return view(peaks.findById(id).map(RunStepAudioPeaks::getPeaks).orElse(computed));
        } finally {
            slots.release();
        }
    }

    private void acquire() {
        boolean acquired;
        try {
            acquired = slots.tryAcquire(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw PeaksRetryLaterException.unavailable();
        }
        if (!acquired) {
            throw PeaksRetryLaterException.busy();
        }
    }

    private static PeaksOutcome decodeFromStore(MediaJobs mediaJobs, String bucket, String key) {
        InputStream in;
        try {
            in = mediaJobs.openDownloadable(bucket, key);
        } catch (MediaStoreUnavailableException e) {
            return new PeaksOutcome.StoreUnavailable();
        }
        try {
            return AudioPeaks.compute(in, MAX_SECONDS);
        } finally {
            try {
                in.close();
            } catch (IOException e) {
                LOG.debug("Closing the sound's stream failed: bucket={} key={}", bucket, key, e);
            }
        }
    }

    private PeaksView view(String stored) {
        if (stored == null) {
            return new PeaksView(null);
        }
        try {
            return new PeaksView(Arrays.stream(json.readValue(stored, double[].class)).boxed().toList());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored waveform is not a number array", e);
        }
    }

    private static String toJson(float[] values) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(Float.toString(values[i]));
        }
        return out.append(']').toString();
    }
}
