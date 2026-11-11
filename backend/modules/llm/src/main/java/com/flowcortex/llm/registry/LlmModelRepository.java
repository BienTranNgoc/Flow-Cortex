package com.flowcortex.llm.registry;

import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface LlmModelRepository extends JpaRepository<LlmModel, String> {

    Page<LlmModel> findAllByOrderByDisplayOrderAscModelKeyAsc(Pageable pageable);

    /**
     * Creates the entry only if no entry holds the id: {@code 1} when created, {@code 0} when the id is taken.
     * One statement, so two concurrent creates of one id give one winner and never overwrite its row.
     */
    @Modifying
    @Query(value = "INSERT INTO llm_model (model_key, provider_key, display_name, max_output_tokens, "
            + "supports_vision, supports_audio, active, display_order, created_at, updated_at) "
            + "VALUES (:modelKey, :providerKey, :displayName, :maxOutputTokens, :supportsVision, "
            + ":supportsAudio, :active, :displayOrder, :now, :now) "
            + "ON CONFLICT (model_key) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("modelKey") String modelKey, @Param("providerKey") String providerKey,
            @Param("displayName") String displayName, @Param("maxOutputTokens") int maxOutputTokens,
            @Param("supportsVision") boolean supportsVision, @Param("supportsAudio") boolean supportsAudio,
            @Param("active") boolean active, @Param("displayOrder") short displayOrder,
            @Param("now") Instant now);
}
