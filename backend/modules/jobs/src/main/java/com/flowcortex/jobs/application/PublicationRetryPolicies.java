package com.flowcortex.jobs.application;

import com.flowcortex.jobs.api.PublicationRetryPolicy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The retry policy per event type. Two policies for one type stop the application at startup, because
 * which one applied would otherwise depend on bean order. Kept apart from {@link ClusterJobRegistry}: the
 * resubmitter is itself a cluster job and reads this, so folding both into one bean would make them
 * depend on each other.
 */
@Component
public class PublicationRetryPolicies {

    private final Map<Class<?>, PublicationRetryPolicy> policies = new HashMap<>();
    private final PublicationRetryPolicy fallback = new DefaultPublicationRetryPolicy();

    public PublicationRetryPolicies(List<PublicationRetryPolicy> policies) {
        for (PublicationRetryPolicy policy : policies) {
            if (this.policies.putIfAbsent(policy.eventType(), policy) != null) {
                throw new IllegalStateException("Two retry policies for event type " + policy.eventType().getName());
            }
        }
    }

    /** The policy of the event's class, or the default of ten attempts a minute apart. */
    public PublicationRetryPolicy policyFor(Class<?> eventType) {
        return policies.getOrDefault(eventType, fallback);
    }
}
