package probetest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Test-only JPA entity for {@link PersistenceValidationTest} (task 2.3). No
 * domain entity exists yet (group 4 adds the first one) — this proves
 * {@code ddl-auto: validate} genuinely validates a mapping against a
 * Flyway-migrated Postgres schema, both when they match and when they don't.
 *
 * <p>Deliberately package-scoped OUTSIDE {@code com.flowcortex} so it is never
 * swept into the real app's entity scan (which is rooted at
 * {@code com.flowcortex}) — {@link PersistenceValidationTest} wires it in
 * explicitly via {@code ApplicationContextRunner}, so no other test sees it.
 */
@Entity
@Table(name = "probe_entity")
class ProbeEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String label;

    protected ProbeEntity() {
        // JPA
    }

    ProbeEntity(UUID id, String label) {
        this.id = id;
        this.label = label;
    }

    UUID getId() {
        return id;
    }

    String getLabel() {
        return label;
    }
}
