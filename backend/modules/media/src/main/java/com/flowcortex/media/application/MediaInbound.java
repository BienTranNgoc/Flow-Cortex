package com.flowcortex.media.application;

import com.flowcortex.media.api.MediaJob;
import com.flowcortex.media.api.MediaJobCompleted;
import com.flowcortex.media.api.MediaJobProgressed;
import com.flowcortex.media.api.MediaOutput;
import com.flowcortex.media.api.MediaProgress;
import com.flowcortex.media.api.MediaResult;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the worker sends back, validated and handed to the modules that wait for it. Each message
 * is published inside a transaction: the workflow module's listeners run after commit, and
 * without one they skip the event and the result is lost.
 */
@Service
public class MediaInbound {

    /** The step segment of an output key after {@code runs/{runId}/steps/}. */
    private static final Pattern STEP_SEGMENT = Pattern.compile("([1-9][0-9]*/).*", Pattern.DOTALL);

    private final Validator validator;
    private final ApplicationEventPublisher events;
    private final String mediaBucket;

    public MediaInbound(Validator validator, ApplicationEventPublisher events, MediaProperties properties) {
        this.validator = validator;
        this.events = events;
        this.mediaBucket = properties.s3().bucket();
    }

    /**
     * @throws ConstraintViolationException if {@code result} breaks the contract, or names an output
     *         outside the media bucket or outside its own run's step prefixes; nothing is published
     */
    @Transactional
    public void acceptResult(MediaResult result) {
        requireValid(result);
        requireOutputsOfItsRun(result);
        events.publishEvent(new MediaJobCompleted(result));
    }

    /** @throws ConstraintViolationException if {@code progress} breaks the contract; nothing is published */
    @Transactional
    public void acceptProgress(MediaProgress progress) {
        requireValid(progress);
        events.publishEvent(new MediaJobProgressed(progress));
    }

    private <T> void requireValid(T message) {
        Set<ConstraintViolation<T>> violations = validator.validate(message);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }

    // The result does not carry the step order, so only the run is checked here; the download
    // checks the exact step before it signs anything.
    private void requireOutputsOfItsRun(MediaResult result) {
        if (result.outputs() == null) {
            return;
        }
        String runSteps = "runs/" + result.runId() + "/steps/";
        for (MediaOutput output : result.outputs()) {
            if (!mediaBucket.equals(output.bucket())) {
                throw new ConstraintViolationException("output " + output.name() + " is in bucket " + output.bucket()
                        + ", not " + mediaBucket, Set.of());
            }
            String key = output.objectKey();
            Matcher step = STEP_SEGMENT.matcher(key.startsWith(runSteps) ? key.substring(runSteps.length()) : "");
            if (!step.matches() || !MediaJob.isBelow(runSteps + step.group(1), key)) {
                throw new ConstraintViolationException("output " + output.name() + " is not stored under " + runSteps
                        + "<step>/: " + key, Set.of());
            }
        }
    }
}
