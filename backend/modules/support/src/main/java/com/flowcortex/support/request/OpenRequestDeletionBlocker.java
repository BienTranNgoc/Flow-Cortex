package com.flowcortex.support.request;

import com.flowcortex.user.api.AccountDeletionBlocker;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** An account with a Help Center request still open cannot be deleted: staff would be answering a member who is gone. */
@Component
class OpenRequestDeletionBlocker implements AccountDeletionBlocker {

    private final RequestRepository requests;

    OpenRequestDeletionBlocker(RequestRepository requests) {
        this.requests = requests;
    }

    @Override
    public String key() {
        return "support.open_request";
    }

    @Override
    public int defaultSortOrder() {
        return 1;
    }

    @Override
    public String defaultMessageVi() {
        return "Bạn có yêu cầu hỗ trợ đang mở. Hãy chờ yêu cầu được giải quyết hoặc liên hệ bộ phận hỗ trợ, rồi thử lại.";
    }

    @Override
    public String defaultMessageEn() {
        return "You have an open Help Center request. Wait until it is resolved, or contact support, then try again.";
    }

    @Override
    public boolean blocks(UUID userId) {
        return requests.existsOpenFor(userId);
    }
}
