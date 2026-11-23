package com.flowcortex.user.roles;

import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.infrastructure.RoleDetailRow;
import com.flowcortex.user.infrastructure.RoleRepository;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates, renames and deletes custom staff roles; system roles are fixed. */
@Service
public class RoleService {

    private static final int MAX_INSERT_ATTEMPTS = 3;
    private static final int MAX_SUFFIX = 10_000;
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    private final RoleRepository roles;

    RoleService(RoleRepository roles) {
        this.roles = roles;
    }

    /**
     * Creates a custom staff role holding {@code keys} and returns its code. The code comes from the name; a taken
     * or reserved code gets the next numeric suffix, and a taken name is a conflict settled by the unique index.
     */
    @Transactional
    public String create(String name, String description, Collection<String> keys) {
        String cleanName = name.strip();
        String cleanDescription = cleanDescription(description);
        List<String> keySet = keys == null ? List.of() : keys.stream().distinct().toList();
        PermissionSetPolicy.check(keySet, PermissionSetPolicy.permissionCatalog()).ifPresent(violation -> {
            throw violation.toException();
        });

        String base = RoleCodes.slug(cleanName);
        int next = 1;
        for (int attempt = 0; attempt < MAX_INSERT_ATTEMPTS; attempt++) {
            Candidate candidate = firstFree(base, next);
            Optional<String> inserted;
            try {
                inserted = roles.insertCustomIfAbsent(candidate.code(), cleanName, cleanDescription);
            } catch (DuplicateKeyException e) {
                throw nameTaken(cleanName);
            }
            if (inserted.isPresent()) {
                roles.replaceKeys(candidate.code(), keySet);
                return candidate.code();
            }
            next = candidate.suffix() + 1;
        }
        throw new ConflictException(new LocalizedMessage("role.codeConflict"));
    }

    /** Changes the name and/or description of a custom role; a {@code null} argument leaves the field as it is. */
    @Transactional
    public void update(String code, String name, String description) {
        RoleDetailRow role = require(code);
        if (role.system()) {
            throw systemImmutable(code);
        }
        String newName = name == null ? role.name() : name.strip();
        String newDescription = description == null ? role.description() : cleanDescription(description);
        try {
            roles.updateText(code, newName, newDescription);
        } catch (DuplicateKeyException e) {
            throw nameTaken(newName);
        }
    }

    /**
     * Deletes an unused custom role with its keys; a role some account still holds answers 409 with the count.
     * Deliberately not transactional: the single DELETE is atomic with its cascade, and an assignment that wins the
     * race raises the foreign-key violation outside any transaction, so the count can still be read for the
     * message (inside one, PostgreSQL refuses every further statement).
     */
    public void delete(String code) {
        RoleDetailRow role = require(code);
        if (role.system()) {
            throw systemImmutable(code);
        }
        int holders = roles.holderCount(code);
        if (holders > 0) {
            throw hasAccounts(holders);
        }
        try {
            roles.delete(code);
        } catch (DataIntegrityViolationException e) {
            if (!isForeignKeyViolation(e)) {
                throw e;
            }
            throw hasAccounts(Math.max(1, roles.holderCount(code)));
        }
    }

    private RoleDetailRow require(String code) {
        return roles.findDetail(code).orElseThrow(() -> notFound(code));
    }

    private Candidate firstFree(String base, int from) {
        for (int n = from; n <= MAX_SUFFIX; n++) {
            String code = n == 1 ? base : RoleCodes.withSuffix(base, n);
            if (!RoleCodes.isReserved(code) && !roles.exists(code)) {
                return new Candidate(code, n);
            }
        }
        throw new ConflictException(new LocalizedMessage("role.codeConflict"));
    }

    private static String cleanDescription(String description) {
        if (description == null) {
            return null;
        }
        String stripped = description.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static boolean isForeignKeyViolation(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && FOREIGN_KEY_VIOLATION.equals(sql.getSQLState())) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    static NotFoundException notFound(String code) {
        return new NotFoundException(new LocalizedMessage("role.notFound", code));
    }

    private static ConflictException nameTaken(String name) {
        return new ConflictException(new LocalizedMessage("role.nameTaken", name));
    }

    private static ConflictException systemImmutable(String code) {
        return new ConflictException(new LocalizedMessage("role.systemImmutable", code));
    }

    private static ConflictException hasAccounts(int count) {
        return new ConflictException(new LocalizedMessage("role.hasAccounts", count));
    }

    private record Candidate(String code, int suffix) {
    }
}
