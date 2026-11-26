package com.flowcortex.user.application;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.user.api.AdminPermissions;
import com.flowcortex.user.api.UnknownRoleException;
import com.flowcortex.user.api.UserQuery;
import com.flowcortex.user.api.UserView;
import com.flowcortex.user.audit.AuditReadRecorder;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.infrastructure.AppUserRepository;
import com.flowcortex.user.infrastructure.UserDirectoryRepository;
import com.flowcortex.user.pii.PiiMask;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the users CSV for the caller's scope. The count and the batches read one snapshot, so the
 * file holds exactly the rows that were counted; over the configured limit nothing is produced.
 *
 * <p>Email and name are written in full only for a caller holding {@code user.account.view_pii}, and
 * only after an audit row for the export is stored; any other caller gets them masked and no row.
 */
@Service
public class UserExportService {

    static final int BATCH_SIZE = 500;
    static final String EXPORT_ACTION = "user.export";

    private final AppUserRepository userRepository;
    private final UserDirectoryRepository directory;
    private final UserExportProperties properties;
    private final AdminPermissions adminPermissions;
    private final AuditReadRecorder recorder;
    private final JsonMapper mapper;
    private final Clock clock;

    UserExportService(AppUserRepository userRepository, UserDirectoryRepository directory,
            UserExportProperties properties, AdminPermissions adminPermissions, AuditReadRecorder recorder,
            JsonMapper mapper, Clock clock) {
        this.userRepository = userRepository;
        this.directory = directory;
        this.properties = properties;
        this.adminPermissions = adminPermissions;
        this.recorder = recorder;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public byte[] export(UUID callerId, UserQuery query, String clientAddress, String userAgent) {
        AppUser caller = userRepository.findById(callerId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("user.notFound", callerId)));
        AccountStatusGuard.requireActive(caller);
        query.role().filter(code -> !directory.roleExists(code)).ifPresent(code -> {
            throw new UnknownRoleException(code);
        });
        UUID scope = UserServiceImpl.scopeOf(caller);
        Instant now = clock.instant();
        long total = directory.count(scope, query, now);
        int max = properties.maxRows();
        if (total > max) {
            throw new UnprocessableEntityException(new LocalizedMessage("user.exportTooLarge", total, max),
                    "user.exportTooLarge");
        }
        boolean unmasked = holdsViewPii(callerId);
        if (unmasked) {
            recorder.recordOrRefuse(callerId, EXPORT_ACTION, "user", null, null, summaryOf(query, total),
                    clientAddress, userAgent);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, UserCsv.BOM + UserCsv.header());
        for (long offset = 0; offset < total; offset += BATCH_SIZE) {
            List<UserView> batch = directory.page(scope, query, now, offset, BATCH_SIZE);
            for (UserView user : batch) {
                write(out, UserCsv.row(unmasked ? user : masked(user)));
            }
            if (batch.size() < BATCH_SIZE) {
                break;
            }
        }
        return out.toByteArray();
    }

    private boolean holdsViewPii(UUID callerId) {
        try {
            adminPermissions.require(callerId, Permission.USER_ACCOUNT_VIEW_PII);
            return true;
        } catch (AccessDeniedException missingKey) {
            return false;
        }
    }

    /** The filters the caller gave plus the number of rows in the file. */
    private ObjectNode summaryOf(UserQuery query, long rowCount) {
        ObjectNode summary = mapper.createObjectNode();
        if (!query.q().isEmpty()) {
            summary.put("q", query.q());
        }
        query.role().ifPresent(role -> summary.put("role", role));
        query.state().ifPresent(state -> summary.put("state", state.wire()));
        summary.put("rowCount", rowCount);
        return summary;
    }

    private static UserView masked(UserView user) {
        return new UserView(user.id(), user.ownerId(), PiiMask.email(user.email()), PiiMask.name(user.name()),
                user.role(), user.roleName(), user.status(), user.createdAt(), user.updatedAt(), user.username(),
                user.state(), user.emailConfirmedAt(), user.lockedUntil(), user.signInMethods(),
                user.adminDisabled(), user.staffTarget());
    }

    private static void write(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }
}
