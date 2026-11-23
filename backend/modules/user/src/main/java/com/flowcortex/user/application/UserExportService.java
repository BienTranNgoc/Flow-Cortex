package com.flowcortex.user.application;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.user.api.UnknownRoleException;
import com.flowcortex.user.api.UserQuery;
import com.flowcortex.user.api.UserView;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.infrastructure.AppUserRepository;
import com.flowcortex.user.infrastructure.UserDirectoryRepository;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the users CSV for the caller's scope. The count and the batches read one snapshot, so the
 * file holds exactly the rows that were counted; over the configured limit nothing is produced.
 */
@Service
public class UserExportService {

    static final int BATCH_SIZE = 500;

    private final AppUserRepository userRepository;
    private final UserDirectoryRepository directory;
    private final UserExportProperties properties;
    private final Clock clock;

    UserExportService(AppUserRepository userRepository, UserDirectoryRepository directory,
            UserExportProperties properties, Clock clock) {
        this.userRepository = userRepository;
        this.directory = directory;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public byte[] export(UUID callerId, UserQuery query) {
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
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, UserCsv.BOM + UserCsv.header());
        for (long offset = 0; offset < total; offset += BATCH_SIZE) {
            List<UserView> batch = directory.page(scope, query, now, offset, BATCH_SIZE);
            for (UserView user : batch) {
                write(out, UserCsv.row(user));
            }
            if (batch.size() < BATCH_SIZE) {
                break;
            }
        }
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }
}
