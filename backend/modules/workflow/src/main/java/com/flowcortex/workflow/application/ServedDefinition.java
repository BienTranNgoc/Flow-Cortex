package com.flowcortex.workflow.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Decides which definition a caller is served: an ordered rule table, the first rule that answers wins. System
 * workflows and owners always run the live draft; any other caller gets their pin, then the published pointer,
 * then the live draft.
 */
public final class ServedDefinition {

    /** What a caller is served. */
    public sealed interface Served permits Pinned, Live, Pointer {
    }

    /** The caller's own pinned version number. */
    public record Pinned(int version) implements Served {
    }

    /** The workflow's live steps. */
    public record Live() implements Served {
    }

    /** The version row the published pointer names. */
    public record Pointer(UUID versionId) implements Served {
    }

    /** Facts about one (workflow, caller) pair; {@code pinnedVersion} and {@code pointerVersionId} may be null. */
    public record Facts(boolean systemOwned, boolean owner, Integer pinnedVersion, UUID pointerVersionId) {
    }

    private static final List<Function<Facts, Optional<Served>>> RULES = List.of(
            facts -> facts.systemOwned() ? Optional.of(new Live()) : Optional.empty(),
            facts -> facts.owner() ? Optional.of(new Live()) : Optional.empty(),
            facts -> facts.pinnedVersion() != null ? Optional.of(new Pinned(facts.pinnedVersion())) : Optional.empty(),
            facts -> facts.pointerVersionId() != null
                    ? Optional.of(new Pointer(facts.pointerVersionId()))
                    : Optional.empty(),
            facts -> Optional.of(new Live()));

    private ServedDefinition() {
    }

    public static Served resolve(Facts facts) {
        for (Function<Facts, Optional<Served>> rule : RULES) {
            Optional<Served> served = rule.apply(facts);
            if (served.isPresent()) {
                return served.get();
            }
        }
        throw new IllegalStateException("the last rule always answers");
    }
}
