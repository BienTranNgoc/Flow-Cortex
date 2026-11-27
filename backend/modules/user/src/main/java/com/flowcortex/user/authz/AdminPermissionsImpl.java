package com.flowcortex.user.authz;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.user.api.AdminAssignee;
import com.flowcortex.user.api.AdminPermissions;
import com.flowcortex.user.application.JwtClaims;
import com.flowcortex.user.totp.TotpRequirement;
import com.flowcortex.user.totp.TotpService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AdminPermissionsImpl implements AdminPermissions {

    private static final int MAX_HOLDERS = 500;
    /** The one eligibility predicate: both the list and the single-account check read it. */
    private static final String HOLDS = "status = 'ACTIVE' AND admin_disabled_at IS NULL AND (role = 'ADMIN' "
            + "OR role IN (SELECT role_code FROM role_permission WHERE permission_key = :key))";

    private final CallerFactsLoader factsLoader;
    private final NamedParameterJdbcTemplate jdbc;
    private final TotpRequirement requirement;
    private final TotpService totp;

    AdminPermissionsImpl(CallerFactsLoader factsLoader, NamedParameterJdbcTemplate jdbc,
            TotpRequirement requirement, TotpService totp) {
        this.factsLoader = factsLoader;
        this.jdbc = jdbc;
        this.requirement = requirement;
        this.totp = totp;
    }

    @Override
    public void require(UUID userId, Permission key) {
        CallerFacts facts = factsLoader.load(userId, sessionPassedMfa());
        AdminAuthorizationDecision.decide(new Posture.AllOf(Set.of(key)), facts, requirement.adminRequired())
                .requireAllowed(() -> totp.isEnabled(userId));
    }

    /** The {@code mfa} claim of the request's token, read the way the interceptor reads it; false without one. */
    private static boolean sessionPassedMfa() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication instanceof JwtAuthenticationToken token && JwtClaims.mfaOf(token.getToken());
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminAssignee> holders(Permission key) {
        return jdbc.query("SELECT id, email, name FROM app_user WHERE " + HOLDS + " ORDER BY email LIMIT "
                + MAX_HOLDERS, new MapSqlParameterSource("key", key.key()),
                (rs, row) -> new AdminAssignee(rs.getObject("id", UUID.class), rs.getString("email"),
                        rs.getString("name")));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean holds(UUID userId, Permission key) {
        Boolean held = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM app_user WHERE id = :id AND " + HOLDS + ")",
                new MapSqlParameterSource("key", key.key()).addValue("id", userId), Boolean.class);
        return Boolean.TRUE.equals(held);
    }
}
