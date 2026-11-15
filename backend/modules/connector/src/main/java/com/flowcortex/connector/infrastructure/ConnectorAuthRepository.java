package com.flowcortex.connector.infrastructure;

import com.flowcortex.connector.domain.ConnectorAuth;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Every statement carries {@code user_id} in its {@code WHERE}; nothing finds or deletes by id
 * alone. Writes are single conditional statements decided by the row count, so a late refresh,
 * usage switch or second revoke on an inactive row changes nothing.
 */
public interface ConnectorAuthRepository extends Repository<ConnectorAuth, UUID> {

    /** Creates the row or replaces it, returning it to active, usage on, not revoked. */
    @Modifying
    @Query(value = "INSERT INTO connector_auth (id, user_id, provider, access_token, refresh_token, expires_at, "
            + "scopes, metadata, is_active, usage_enabled, revoked_at, created_at, updated_at) "
            + "VALUES (:id, :userId, :provider, :accessToken, CAST(:refreshToken AS text), "
            + "CAST(:expiresAt AS timestamptz), CAST(:scopes AS text[]), CAST(:metadata AS jsonb), "
            + "true, true, NULL, :now, :now) "
            + "ON CONFLICT (user_id, provider) DO UPDATE SET access_token = EXCLUDED.access_token, "
            + "refresh_token = EXCLUDED.refresh_token, expires_at = EXCLUDED.expires_at, "
            + "scopes = EXCLUDED.scopes, metadata = EXCLUDED.metadata, is_active = true, "
            + "usage_enabled = true, revoked_at = NULL, updated_at = EXCLUDED.updated_at", nativeQuery = true)
    int upsert(@Param("id") UUID id, @Param("userId") UUID userId, @Param("provider") String provider,
            @Param("accessToken") String accessToken, @Param("refreshToken") String refreshToken,
            @Param("expiresAt") Instant expiresAt, @Param("scopes") String[] scopes,
            @Param("metadata") String metadata, @Param("now") Instant now);

    /** Active rows only; a {@code null} refresh token or scopes keeps the stored value, the usage flag is untouched. */
    @Modifying
    @Query(value = "UPDATE connector_auth SET access_token = :accessToken, "
            + "refresh_token = COALESCE(CAST(:refreshToken AS text), refresh_token), "
            + "expires_at = CAST(:expiresAt AS timestamptz), "
            + "scopes = COALESCE(CAST(:scopes AS text[]), scopes), updated_at = :now "
            + "WHERE user_id = :userId AND provider = :provider AND is_active", nativeQuery = true)
    int refresh(@Param("userId") UUID userId, @Param("provider") String provider,
            @Param("accessToken") String accessToken, @Param("refreshToken") String refreshToken,
            @Param("expiresAt") Instant expiresAt, @Param("scopes") String[] scopes, @Param("now") Instant now);

    @Modifying
    @Query(value = "UPDATE connector_auth SET is_active = false, revoked_at = :now, updated_at = :now "
            + "WHERE user_id = :userId AND provider = :provider AND is_active", nativeQuery = true)
    int revoke(@Param("userId") UUID userId, @Param("provider") String provider, @Param("now") Instant now);

    @Modifying
    @Query(value = "UPDATE connector_auth SET usage_enabled = :enabled, updated_at = :now "
            + "WHERE user_id = :userId AND provider = :provider AND is_active", nativeQuery = true)
    int setUsage(@Param("userId") UUID userId, @Param("provider") String provider,
            @Param("enabled") boolean enabled, @Param("now") Instant now);

    @Modifying
    @Query(value = "DELETE FROM connector_auth WHERE user_id = :userId AND provider = :provider", nativeQuery = true)
    int deleteByUserAndProvider(@Param("userId") UUID userId, @Param("provider") String provider);

    Optional<ConnectorAuth> findByUserIdAndProvider(UUID userId, String provider);

    List<ConnectorAuth> findByUserIdOrderByProvider(UUID userId);

    List<ConnectorAuth> findByUserIdAndActiveTrueOrderByProvider(UUID userId);
}
