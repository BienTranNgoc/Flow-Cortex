package com.flowcortex.content.document;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ContentDocumentRepository extends JpaRepository<ContentDocument, UUID>, ContentDocumentInserts {

    Optional<ContentDocument> findBySlugAndStatus(String slug, String status);

    List<ContentDocument> findBySlugIn(Collection<String> slugs);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM ContentDocument d WHERE d.slug = :slug AND d.status = :status")
    Optional<ContentDocument> findForUpdate(@Param("slug") String slug, @Param("status") String status);
}
