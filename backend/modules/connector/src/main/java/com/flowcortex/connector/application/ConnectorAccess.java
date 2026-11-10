package com.flowcortex.connector.application;

import com.flowcortex.connector.api.ConnectorConnection;
import com.flowcortex.connector.api.ConnectorCredentials;
import com.flowcortex.connector.api.ConnectorException;
import com.flowcortex.connector.api.ConnectorException.Reason;
import com.flowcortex.connector.api.ConnectorTokens;
import com.flowcortex.connector.api.CredentialUnreadableException;
import com.flowcortex.connector.application.ConnectorUseGate.GateInput;
import com.flowcortex.connector.application.ConnectorUseGate.Operation;
import com.flowcortex.connector.application.ConnectorUseGate.Verdict;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.UserDirectory;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * The only door through which a connector service reads a stored credential: it resolves the member,
 * runs {@link ConnectorUseGate}, and reads tokens only after the gate allowed the call.
 */
@Service
public class ConnectorAccess {

    /** The connection view and, when asked for, its plaintext tokens ({@code null} otherwise). */
    public record Granted(ConnectorConnection connection, ConnectorTokens tokens) {
    }

    private final UserDirectory users;
    private final ConnectorCredentials credentials;

    ConnectorAccess(UserDirectory users, ConnectorCredentials credentials) {
        this.users = users;
        this.credentials = credentials;
    }

    /** USE: sending or calling. Fails like the gate says, then returns the connection and its tokens. */
    public Granted use(UUID memberId, String provider, boolean switchOn) {
        return acquire(memberId, provider, switchOn, Operation.USE, true);
    }

    /** MANAGE: reading or editing the connection; works with usage off. Tokens only when {@code withTokens}. */
    public Granted manage(UUID memberId, String provider, boolean switchOn, boolean withTokens) {
        return acquire(memberId, provider, switchOn, Operation.MANAGE, withTokens);
    }

    /** The member and switch checks alone, for an operation that creates the connection. */
    public void requireMember(UUID memberId, boolean switchOn) {
        enforce(ConnectorUseGate.decide(new GateInput(isSuspended(memberId), switchOn, true, true, Operation.MANAGE)));
    }

    private Granted acquire(UUID memberId, String provider, boolean switchOn, Operation operation,
            boolean withTokens) {
        boolean suspended = isSuspended(memberId);
        Optional<ConnectorConnection> connection = suspended || !switchOn
                ? Optional.empty() : credentials.find(memberId, provider);
        boolean connected = connection.map(ConnectorConnection::active).orElse(false);
        boolean usageEnabled = connection.map(ConnectorConnection::usageEnabled).orElse(false);
        enforce(ConnectorUseGate.decide(new GateInput(suspended, switchOn, connected, usageEnabled, operation)));
        ConnectorTokens tokens = withTokens ? readTokens(memberId, provider) : null;
        return new Granted(connection.orElseThrow(), tokens);
    }

    private boolean isSuspended(UUID memberId) {
        try {
            users.findById(memberId)
                    .orElseThrow(() -> new NotFoundException(new LocalizedMessage("user.current.notFound", memberId)));
            return false;
        } catch (AccessDeniedException ex) {
            return true;
        }
    }

    private ConnectorTokens readTokens(UUID memberId, String provider) {
        try {
            return credentials.readTokens(memberId, provider)
                    .orElseThrow(() -> ConnectorException.of(Reason.NOT_CONNECTED));
        } catch (CredentialUnreadableException ex) {
            throw ConnectorException.of(Reason.CREDENTIAL_UNREADABLE);
        }
    }

    private static void enforce(Verdict verdict) {
        if (!verdict.allowed()) {
            throw verdict.exception();
        }
    }
}
