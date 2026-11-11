package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.user.domain.Role;
import com.flowcortex.user.api.UserView;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
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

        assertThat(keysOf("OPERATION_MANAGER")).hasSize(18).isEqualTo(operationManager);
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
    void operationManagerAloneAmongStaffHoldsUserAccountExport() {
        String export = "user.account.export";

        assertThat(keysOf("OPERATION_MANAGER")).contains(export);
        assertThat(keysOf("CUSTOMER_SUPPORT")).doesNotContain(export);
        assertThat(keysOf("CONTENT_EDITOR")).doesNotContain(export);
        assertThat(keysOf("PLATFORM_OPERATOR")).doesNotContain(export);
        assertThat(keysOf("ADMIN")).isEmpty();
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
    void theRoleEnumFlagsMatchTheRoleTable() {
        Map<String, boolean[]> rows = jdbc.query("SELECT code, staff, super_admin FROM role", rs -> {
            Map<String, boolean[]> map = new java.util.HashMap<>();
            while (rs.next()) {
                map.put(rs.getString("code"), new boolean[] {rs.getBoolean("staff"), rs.getBoolean("super_admin")});
            }
            return map;
        });

        assertThat(rows.keySet()).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(Role.values()).map(Role::name).toList());
        for (Role role : Role.values()) {
            boolean[] flags = rows.get(role.name());
            assertThat(role.isStaff()).as("staff flag of %s", role).isEqualTo(flags[0]);
            assertThat(role.isSuperAdmin()).as("super_admin flag of %s", role).isEqualTo(flags[1]);
        }
    }

    @Test
    void everyExistingRoleValueSatisfiesTheRoleForeignKey() {
        UserView admin = userService.createInitialAdmin(uniqueEmail("rbac-fk"), "correct-password-1", "Rbac Fk");
        for (Role role : Role.values()) {
            if (role == Role.ADMIN) {
                continue;
            }
            UserView created = userService.createUser(
                    admin.id(), uniqueEmail("rbac-fk-" + role), "correct-password-1", role.name(), role.name());
            assertThat(created.role()).isEqualTo(role.name());
        }

        assertThatThrownBy(() -> jdbc.update("UPDATE app_user SET role = 'NOT_A_ROLE' WHERE id = ?", admin.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
