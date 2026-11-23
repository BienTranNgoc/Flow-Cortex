package com.flowcortex.user.oauth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * A dynamically registered client. Only the SHA-256 of its secret is kept; a client that
 * authenticates with {@code none} has no secret at all.
 */
@Entity
@Table(name = "oauth_client")
class OAuthClient {

    static final String AUTH_POST = "client_secret_post";
    static final String AUTH_BASIC = "client_secret_basic";
    static final String AUTH_NONE = "none";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    @Id
    @Column(name = "client_id", nullable = false, updatable = false)
    private String clientId;

    @Column(name = "client_secret_hash", updatable = false)
    private String clientSecretHash;

    @Column(name = "client_name", nullable = false, updatable = false)
    private String clientName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "redirect_uris", nullable = false, updatable = false)
    private String redirectUris;

    @Column(name = "auth_method", nullable = false, updatable = false)
    private String authMethod;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OAuthClient() {
    }

    OAuthClient(String clientId, String clientSecretHash, String clientName, List<String> redirectUris,
            String authMethod, Instant createdAt) {
        this.clientId = clientId;
        this.clientSecretHash = clientSecretHash;
        this.clientName = clientName;
        this.redirectUris = JSON.writeValueAsString(redirectUris);
        this.authMethod = authMethod;
        this.createdAt = createdAt;
    }

    String clientId() {
        return clientId;
    }

    String secretHash() {
        return clientSecretHash;
    }

    String clientName() {
        return clientName;
    }

    List<String> redirectUris() {
        return JSON.readValue(redirectUris, STRINGS);
    }

    String authMethod() {
        return authMethod;
    }

    Instant createdAt() {
        return createdAt;
    }

    boolean isPublic() {
        return AUTH_NONE.equals(authMethod);
    }
}
