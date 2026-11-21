package com.flowcortex.user.preferences;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface AccountPreferenceRepository extends JpaRepository<AccountPreference, UUID> {

    /** Creates the empty row when absent, so the next locked read always finds one. */
    @Modifying
    @Query(value = """
            INSERT INTO account_preference (user_id, ui, privacy, updated_at)
            VALUES (:userId, '{}'::jsonb, '{}'::jsonb, :now)
            ON CONFLICT (user_id) DO NOTHING
            """, nativeQuery = true)
    void insertIfAbsent(@Param("userId") UUID userId, @Param("now") Instant now);

    /** Row-locked read: concurrent merges of different keys run one after the other and keep both. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM AccountPreference p WHERE p.userId = :userId")
    Optional<AccountPreference> findByUserIdForUpdate(@Param("userId") UUID userId);
}
