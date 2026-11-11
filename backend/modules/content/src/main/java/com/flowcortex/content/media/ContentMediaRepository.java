package com.flowcortex.content.media;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** The only reader and writer of {@code content_media}; rows are inserted and read, never changed. */
public interface ContentMediaRepository extends Repository<ContentMedia, UUID> {

    ContentMedia save(ContentMedia media);

    Optional<ContentMedia> findById(UUID id);

    Page<ContentMedia> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    Page<ContentMedia> findByKindOrderByCreatedAtDescIdDesc(String kind, Pageable pageable);

    /** Which of {@code ids} name a library item, in one query. */
    @Query("SELECT m.id FROM ContentMedia m WHERE m.id IN :ids")
    List<UUID> findExistingIds(@Param("ids") Collection<UUID> ids);
}
