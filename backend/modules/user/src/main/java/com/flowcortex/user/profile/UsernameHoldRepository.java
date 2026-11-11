package com.flowcortex.user.profile;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface UsernameHoldRepository extends JpaRepository<UsernameHold, String> {

    /** The unexpired hold of {@code username} when it belongs to anyone but {@code userId}. */
    @Query("SELECT h FROM UsernameHold h WHERE h.username = :username AND h.userId <> :userId AND h.expiresAt > :now")
    Optional<UsernameHold> findActiveForOther(@Param("username") String username, @Param("userId") UUID userId,
            @Param("now") Instant now);

    /** The unexpired hold of {@code username}, whoever owns it. */
    @Query("SELECT h FROM UsernameHold h WHERE h.username = :username AND h.expiresAt > :now")
    Optional<UsernameHold> findActive(@Param("username") String username, @Param("now") Instant now);

    /** A name releases at most once per row: a repeat release overwrites the earlier hold. */
    @Modifying
    @Query(value = """
            INSERT INTO username_hold (username, user_id, expires_at, created_at)
            VALUES (:username, :userId, :expiresAt, :now)
            ON CONFLICT (username) DO UPDATE
               SET user_id = EXCLUDED.user_id, expires_at = EXCLUDED.expires_at, created_at = EXCLUDED.created_at
            """, nativeQuery = true)
    void upsert(@Param("username") String username, @Param("userId") UUID userId,
            @Param("expiresAt") Instant expiresAt, @Param("now") Instant now);

    @Modifying
    @Query("DELETE FROM UsernameHold h WHERE h.username = :username")
    void deleteByUsername(@Param("username") String username);
}
