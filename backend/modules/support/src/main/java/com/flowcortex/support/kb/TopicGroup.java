package com.flowcortex.support.kb;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A topic group of the knowledge base; groups are listed by {@code position}, then name. */
@Entity
@Table(name = "support_topic_group")
class TopicGroup {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, columnDefinition = "text")
    private String name;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TopicGroup() {
    }

    TopicGroup(String name, int position, Instant now) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.position = position;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void rename(String newName, Instant now) {
        this.name = newName;
        this.updatedAt = now;
    }

    void moveTo(int newPosition, Instant now) {
        if (position != newPosition) {
            this.position = newPosition;
            this.updatedAt = now;
        }
    }

    UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    int getPosition() {
        return position;
    }
}
