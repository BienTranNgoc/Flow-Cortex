package com.flowcortex.jobs.application;

import com.flowcortex.jobs.api.ClusterJob;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The validated job beans in run order. Anything malformed stops the application at startup, because a bad
 * name or interval would otherwise surface as a failed schedule write or a job that never runs.
 */
@Component
public class ClusterJobRegistry {

    private static final Pattern NAME = Pattern.compile("[a-z0-9.-]{3,64}");

    private final List<ClusterJob> jobs;

    public ClusterJobRegistry(List<ClusterJob> jobs) {
        Set<String> names = new HashSet<>();
        for (ClusterJob job : jobs) {
            String name = job.name();
            if (name == null || !NAME.matcher(name).matches()) {
                throw new IllegalStateException(
                        "Cluster job name '" + name + "' does not match [a-z0-9.-]{3,64}");
            }
            if (!names.add(name)) {
                throw new IllegalStateException("Two cluster jobs share the name '" + name + "'");
            }
            if (job.interval() == null || job.interval().isZero() || job.interval().isNegative()) {
                throw new IllegalStateException("Cluster job '" + name + "' needs a positive interval, got "
                        + job.interval());
            }
            if (job.lease() == null || job.lease().isZero() || job.lease().isNegative()) {
                throw new IllegalStateException("Cluster job '" + name + "' needs a positive lease, got "
                        + job.lease());
            }
        }
        this.jobs = jobs.stream()
                .sorted(Comparator.comparing(ClusterJob::interval).thenComparing(ClusterJob::name))
                .toList();
    }

    /** The jobs sorted by interval, then name: the order a pass runs them in. */
    public List<ClusterJob> jobs() {
        return jobs;
    }
}
