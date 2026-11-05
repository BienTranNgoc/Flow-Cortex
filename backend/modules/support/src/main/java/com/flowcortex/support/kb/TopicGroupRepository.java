package com.flowcortex.support.kb;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface TopicGroupRepository extends JpaRepository<TopicGroup, UUID> {

    List<TopicGroup> findAllByOrderByPositionAscNameAsc();

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, UUID id);

    @Query("SELECT COALESCE(MAX(g.position), 0) FROM TopicGroup g")
    int maxPosition();
}
