package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.user.domain.Role;
import com.flowcortex.user.api.UserView;
import com.flowcortex.user.roles.PermissionSetPolicy;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The migrated database holds the permission catalog, the seven roles and their seeded keys. */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class RbacCatalogSeedIT extends AbstractUserModuleIT {

    @Autowired
    private JdbcTemplate jdbc;

    private Set<String> keysOf(String role) {
        return new TreeSet<>(jdbc.queryForList(
                "SELECT permission_key FROM role_permission WHERE role_code = ?", String.class, role));
    }

    private static Set<String> keys(Permission... permissions) {
        return Arrays.stream(permissions).map(Permission::key).collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void thePermissionTableHoldsExactlyTheCatalog() {
        Map<String, Boolean> rows = jdbc.query("SELECT key, super_admin_only FROM permission",
                rs -> {
                    Map<String, Boolean> map = new java.util.HashMap<>();
                    while (rs.next()) {
                        map.put(rs.getString("key"), rs.getBoolean("super_admin_only"));
                    }
                    return map;
                });

        Map<String, Boolean> catalog = Arrays.stream(Permission.values())
                .collect(Collectors.toMap(Permission::key, Permission::superAdminOnly));
        assertThat(catalog).hasSize(22);
        assertThat(rows).isEqualTo(catalog);

        List<String> orderedKeys = jdbc.queryForList("SELECT key FROM permission ORDER BY sort_order", String.class);
        assertThat(orderedKeys).isEqualTo(Arrays.stream(Permission.values()).map(Permission::key).toList());
    }

    @Test
    void eachStaffRoleHoldsItsSeededSet() {
        Set<String> operationManager = new TreeSet<>(keys(Permission.values()));
        operationManager.removeIf(key -> Permission.fromKey(key).orElseThrow().superAdminOnly());
        operationManager.remove(Permission.WORKFLOW_WORKFLOW_MANAGE.key());

        assertThat(keysOf("OPERATION_MANAGER")).hasSize(17).isEqualTo(operationManager);
        assertThat(keysOf("CUSTOMER_SUPPORT")).isEqualTo(keys(
                Permission.SUPPORT_TICKET_VIEW, Permission.SUPPORT_TICKET_REPLY, Permission.SUPPORT_TICKET_CLOSE,
                Permission.USER_ACCOUNT_VIEW, Permission.WORKFLOW_RUN_VIEW));
        assertThat(keysOf("CONTENT_EDITOR")).isEqualTo(keys(
                Permission.CONTENT_HELP_TOPIC_VIEW, Permission.CONTENT_HELP_TOPIC_MANAGE,
                Permission.CONTENT_HELP_ARTICLE_VIEW, Permission.CONTENT_HELP_ARTICLE_MANAGE));
        assertThat(keysOf("PLATFORM_OPERATOR")).isEqualTo(keys(
                Permission.PLATFORM_SETTING_VIEW, Permission.PLATFORM_SETTING_UPDATE,
                Permission.SUPPORT_TICKET_VIEW, Permission.SUPPORT_TICKET_REPLY,
                Permission.USER_ACCOUNT_VIEW, Permission.WORKFLOW_RUN_VIEW));

        Integer workflowManageHolders = jdbc.queryForObject(
                "SELECT count(*) FROM role_permission WHERE permission_key = ?", Integer.class,
                Permission.WORKFLOW_WORKFLOW_MANAGE.key());
        assertThat(workflowManageHolders).isZero();
    }

    @Test
    void everySeededStaffSetPassesThePermissionSetPolicy() {
        List<String> staffRoles = jdbc.queryForList(
                "SELECT code FROM role WHERE staff AND NOT super_admin AND system ORDER BY code", String.class);

        assertThat(staffRoles).isNotEmpty();
        for (String role : staffRoles) {
            assertThat(PermissionSetPolicy.check(keysOf(role), PermissionSetPolicy.permissionCatalog()))
                    .as("seeded set of %s", role).isEmpty();
        }
    }

    @Test
    void noRoleButAdminHoldsASuperAdminOnlyKey() {
        Integer holders = jdbc.queryForObject(
                "SELECT count(*) FROM role_permission rp JOIN permission p ON p.key = rp.permission_key "
                        + "JOIN role r ON r.code = rp.role_code WHERE p.super_admin_only AND NOT r.super_admin",
                Integer.class);
        assertThat(holders).isZero();
        assertThat(keysOf("ADMIN")).isEmpty();
        assertThat(keysOf("MEMBER")).isEmpty();
        assertThat(keysOf("CREATOR")).isEmpty();
    }

    @Test
    void systemRoleManageIsSeededSuperAdminOnly() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT module, feature, action, super_admin_only FROM permission WHERE key = 'system.role.manage'");

        assertThat(row).containsEntry("module", "system").containsEntry("feature", "role")
                .containsEntry("action", "manage").containsEntry("super_admin_only", true);
        assertThat(keysOf("ADMIN")).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM role_permission WHERE permission_key = 'system.role.manage'", Integer.class))
                .isZero();
    }

    @Test
    void everySystemRoleHasItsSeededName() {
        Map<String, String> names = jdbc.query("SELECT code, name FROM role", rs -> {
            Map<String, String> map = new java.util.HashMap<>();
            while (rs.next()) {
                map.put(rs.getString("code"), rs.getString("name"));
            }
            return map;
        });

        assertThat(names).containsEntry("ADMIN", "Super admin")
                .containsEntry("OPERATION_MANAGER", "Operation manager")
                .containsEntry("CUSTOMER_SUPPORT", "Customer support")
                .containsEntry("CONTENT_EDITOR", "Content editor")
                .containsEntry("PLATFORM_OPERATOR", "Platform operator")
                .containsEntry("MEMBER", "Member")
                .containsEntry("CREATOR", "Creator");
        assertThat(jdbc.queryForObject("SELECT permissions_version FROM role WHERE code = 'ADMIN'", Integer.class))
                .isZero();
    }

    @Test
    void aSecondRoleWithTheSameTrimmedCaseInsensitiveNameIsRefusedByTheIndex() {
        insertCustomRole("INDEX_PROBE_ONE", "Index probe");
        try {
            assertThatThrownBy(() -> insertCustomRole("INDEX_PROBE_TWO", " index PROBE "))
                    .isInstanceOf(DuplicateKeyException.class)
                    .hasMessageContaining("uq_role_name_ci");
            assertThatThrownBy(() -> insertCustomRole("INDEX_PROBE_THREE", "customer support"))
                    .isInstanceOf(DuplicateKeyException.class)
                    .hasMessageContaining("uq_role_name_ci");
        } finally {
            jdbc.update("DELETE FROM role WHERE code = 'INDEX_PROBE_ONE'");
        }
    }

    @Test
    void aCustomRoleCannotBeSuperAdminOrEndUser() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO role (code, name, staff, super_admin, system, sort_order) "
                        + "VALUES ('BAD_SUPER', 'Bad super', true, true, false, 1000)"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_role_custom_is_staff");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO role (code, name, staff, super_admin, system, sort_order) "
                        + "VALUES ('BAD_END_USER', 'Bad end user', false, false, false, 1000)"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_role_custom_is_staff");
    }

    @Test
    void aLowercaseCodeIsRefused() {
        assertThatThrownBy(() -> insertCustomRole("lower_case", "Lower case"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_role_code_format");
    }

    private void insertCustomRole(String code, String name) {
        jdbc.update("INSERT INTO role (code, name, staff, super_admin, system, sort_order) "
                + "VALUES (?, ?, true, false, false, 1000)", code, name);
    }

    @Test
    void everyExistingRoleValueSatisfiesTheRoleForeignKey() {
        UserView admin = userService.createInitialAdmin(uniqueEmail("rbac-fk"), "correct-password-1", "Rbac Fk");
        for (Role role : List.of(Role.MEMBER, Role.CREATOR, Role.OPERATION_MANAGER, Role.CUSTOMER_SUPPORT,
                Role.CONTENT_EDITOR, Role.PLATFORM_OPERATOR)) {
            UserView created = userService.createUser(
                    admin.id(), uniqueEmail("rbac-fk-" + role), "correct-password-1", role.code(), role.code());
            assertThat(created.role()).isEqualTo(role.code());
        }

        assertThatThrownBy(() -> jdbc.update("UPDATE app_user SET role = 'NOT_A_ROLE' WHERE id = ?", admin.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
