package com.flowcortex.user.oauth;

import com.flowcortex.user.application.OpaqueTokens;
import com.flowcortex.user.oauth.ClientCredentials.Credentials;
import com.flowcortex.user.oauth.ClientRegistrationRules.Valid;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registers clients and authenticates them at the token and revocation endpoints. */
@Service
class OAuthClientService {

    private static final Logger LOG = LoggerFactory.getLogger(OAuthClientService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OAuthClientRepository clients;
    private final Clock clock;

    OAuthClientService(OAuthClientRepository clients, Clock clock) {
        this.clients = clients;
        this.clock = clock;
    }

    /** {@code secret} is the raw value, present only here and null for a client that authenticates with {@code none}. */
    record Registered(OAuthClient client, String secret) {
    }

    @Transactional
    Registered register(Valid registration) {
        byte[] idBytes = new byte[12];
        RANDOM.nextBytes(idBytes);
        String secret = OAuthClient.AUTH_NONE.equals(registration.authMethod()) ? null
                : OpaqueTokens.generateRawToken();
        OAuthClient client = new OAuthClient("mcp_" + HexFormat.of().formatHex(idBytes),
                secret == null ? null : OpaqueTokens.hash(secret), registration.clientName(),
                registration.redirectUris(), registration.authMethod(), clock.instant());
        clients.save(client);
        LOG.info("oauth_client_registered client_id={} auth_method={}", client.clientId(), client.authMethod());
        return new Registered(client, secret);
    }

    @Transactional(readOnly = true)
    Optional<OAuthClient> find(String clientId) {
        return clientId == null ? Optional.empty() : clients.findById(clientId);
    }

    /** The client the credentials identify and prove, or empty for an unknown client or a wrong or missing secret. */
    @Transactional(readOnly = true)
    Optional<OAuthClient> authenticate(Credentials credentials) {
        return find(credentials.clientId()).filter(client -> ClientCredentials.verify(client, credentials));
    }
}
