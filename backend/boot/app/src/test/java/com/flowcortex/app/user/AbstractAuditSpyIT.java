package com.flowcortex.app.user;

import com.flowcortex.user.audit.JdbcAdminAuditWriter;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * One application context for every IT that spies on the audit writer or the viewer's statements: the
 * properties and spies are declared once here, so those ITs share a single cached context instead of each
 * booting its own in the one test JVM.
 */
@TestPropertySource(properties = {
    "flowcortex.users.export.max-rows=2",
    "flowcortex.admin-audit.export-max-rows=5"
})
abstract class AbstractAuditSpyIT extends AbstractUserModuleIT {

    @MockitoSpyBean
    protected JdbcAdminAuditWriter writer;

    @MockitoSpyBean
    protected NamedParameterJdbcTemplate namedJdbc;
}
