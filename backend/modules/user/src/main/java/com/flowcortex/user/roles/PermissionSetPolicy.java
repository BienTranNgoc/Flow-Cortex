package com.flowcortex.user.roles;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.UnprocessableEntityException;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The rules a staff role's key set must satisfy, shared by create and replace: every key exists, none is
 * super-admin-only, and a feature that has a view action is never granted another action without it.
 */
public final class PermissionSetPolicy {

    private static final String VIEW = "view";

    /** One catalog entry; the feature id is the key without its action. */
    public record CatalogKey(String key, boolean superAdminOnly) {

        String featureId() {
            return key.substring(0, key.lastIndexOf('.'));
        }

        String action() {
            return key.substring(key.lastIndexOf('.') + 1);
        }
    }

    public enum ViolationCode {
        PERMISSION_UNKNOWN,
        PERMISSION_SUPER_ADMIN_ONLY,
        PERMISSION_SET_INCONSISTENT;

        LocalizedMessage message(String subjects) {
            return switch (this) {
                case PERMISSION_UNKNOWN -> new LocalizedMessage("role.permissions.unknown", subjects);
                case PERMISSION_SUPER_ADMIN_ONLY -> new LocalizedMessage("role.permissions.superAdminOnly", subjects);
                case PERMISSION_SET_INCONSISTENT -> new LocalizedMessage("role.permissions.missingView", subjects);
            };
        }
    }

    /** The first rule a set breaks, with every key (or feature id) that breaks it, sorted. */
    public record Violation(ViolationCode code, List<String> subjects) {

        /** The 422 problem: {@code code} is the machine code, {@code errors} is keyed by each offending subject. */
        public UnprocessableEntityException toException() {
            Map<String, LocalizedMessage> errors = new LinkedHashMap<>();
            for (String subject : subjects) {
                errors.put(subject, code.message(subject));
            }
            return new UnprocessableEntityException(code.message(String.join(", ", subjects)), code.name(), errors);
        }
    }

    private PermissionSetPolicy() {
    }

    /** The catalog the server enforces: the {@link Permission} enum. */
    public static List<CatalogKey> permissionCatalog() {
        return Arrays.stream(Permission.values()).map(p -> new CatalogKey(p.key(), p.superAdminOnly())).toList();
    }

    /** Checks in the order unknown, super-admin-only, missing view; an empty result means the set is valid. */
    public static Optional<Violation> check(Collection<String> keys, List<CatalogKey> catalog) {
        Set<String> held = new TreeSet<>(keys);
        Map<String, CatalogKey> byKey = catalog.stream()
                .collect(Collectors.toMap(CatalogKey::key, k -> k, (a, b) -> a));

        List<String> unknown = held.stream().filter(k -> !byKey.containsKey(k)).toList();
        if (!unknown.isEmpty()) {
            return Optional.of(new Violation(ViolationCode.PERMISSION_UNKNOWN, unknown));
        }
        List<String> superAdminOnly = held.stream().filter(k -> byKey.get(k).superAdminOnly()).toList();
        if (!superAdminOnly.isEmpty()) {
            return Optional.of(new Violation(ViolationCode.PERMISSION_SUPER_ADMIN_ONLY, superAdminOnly));
        }
        Set<String> featuresWithView = catalog.stream().filter(k -> VIEW.equals(k.action()))
                .map(CatalogKey::featureId).collect(Collectors.toSet());
        List<String> missingView = held.stream().map(byKey::get)
                .filter(k -> featuresWithView.contains(k.featureId()))
                .filter(k -> !held.contains(k.featureId() + "." + VIEW))
                .map(CatalogKey::featureId).distinct().sorted().toList();
        if (!missingView.isEmpty()) {
            return Optional.of(new Violation(ViolationCode.PERMISSION_SET_INCONSISTENT, missingView));
        }
        return Optional.empty();
    }
}
