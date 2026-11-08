package com.flowcortex.support.kb;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ArticleRepository extends JpaRepository<Article, UUID> {

    Optional<Article> findBySlug(String slug);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, UUID id);

    List<Article> findByStatusOrderByTitleAsc(String status);

    List<Article> findByStatusAndFeaturedPositionNotNullOrderByFeaturedPositionAsc(String status);

    List<Article> findAllByOrderByUpdatedAtDesc();

    long countByGroupId(UUID groupId);

    long countByStatusAndFeaturedPositionNotNull(String status);

    List<Article> findByFeaturedPositionNotNull();

    @Query("SELECT a.groupId, COUNT(a) FROM Article a GROUP BY a.groupId")
    List<Object[]> countByGroup();
}
