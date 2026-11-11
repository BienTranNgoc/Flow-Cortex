package com.flowcortex.user.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.shared.web.audit.AdminAudit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminAuditExceptionRecorderTest {

    private final AdminAuditExceptionRecorder recorder = new AdminAuditExceptionRecorder();

    @AdminAudit("thing.update")
    void annotated() {}

    @Test
    void noPendingAuditIsANoOp() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        var result = recorder.resolveException(request, new MockHttpServletResponse(), null, new IllegalStateException());

        assertThat(result).isNull();
        assertThat(request.getAttribute(PendingAudit.ATTRIBUTE)).isNull();
    }

    @Test
    void storesTheExceptionInThePendingAudit() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        AdminAudit annotation = getClass().getDeclaredMethod("annotated").getAnnotation(AdminAudit.class);
        PendingAudit pending = new PendingAudit(annotation, UUID.randomUUID(), ActorSnapshot.UNKNOWN, "1.2.3.4", "ua", Map.of());
        request.setAttribute(PendingAudit.ATTRIBUTE, pending);
        IllegalStateException failure = new IllegalStateException("boom");

        var result = recorder.resolveException(request, new MockHttpServletResponse(), null, failure);

        assertThat(result).isNull();
        assertThat(pending.exception()).isSameAs(failure);
    }
}
