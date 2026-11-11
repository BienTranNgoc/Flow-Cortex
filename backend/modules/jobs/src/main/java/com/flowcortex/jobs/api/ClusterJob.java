package com.flowcortex.jobs.api;

import java.time.Duration;

/**
 * A periodic job that runs on one instance at a time. The cluster runner calls {@link #run()} when the
 * job is due, waits {@link #interval()} after it finishes (or fails), and lets another instance take the
 * job over once {@link #lease()} has passed without a finish.
 */
public interface ClusterJob {

    /** Stable key of the job's schedule row: {@code <module>.<kebab-case job>}, matching {@code [a-z0-9.-]{3,64}}. */
    String name();

    /** Wait between the end of one run and the start of the next; positive. */
    Duration interval();

    /** How long a claim holds before another instance may take the job over. */
    default Duration lease() {
        return Duration.ofMinutes(10);
    }

    /** One pass of the job. A thrown exception is recorded and does not stop the other jobs. */
    void run();
}
