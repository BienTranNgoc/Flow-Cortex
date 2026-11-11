package com.flowcortex.content.document;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/** Insert and read only: snapshots are never updated or deleted. */
interface ContentRevisionRepository extends Repository<ContentRevision, UUID> {

    ContentRevision save(ContentRevision revision);

    Page<ContentRevision> findBySlugOrderByCreatedAtDescIdDesc(String slug, Pageable pageable);

    Optional<ContentRevision> findByIdAndSlug(UUID id, String slug);

    Page<ContentRevision> findBySlugAndKindOrderByCreatedAtDescIdDesc(String slug, String kind, Pageable pageable);

    Optional<ContentRevision> findByIdAndSlugAndKind(UUID id, String slug, String kind);
}
