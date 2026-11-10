package com.flowcortex.shared.security.authz;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The closed catalog of admin permission keys, {@code <module>.<feature>.<action>}. A route posture
 * names constants of this enum, so a key that does not exist fails compilation. Declaration order is
 * the display order and mirrors {@code permission.sort_order} in the database.
 */
public enum Permission {
    USER_ACCOUNT_VIEW("user.account.view", false),
    USER_ACCOUNT_CREATE("user.account.create", false),
    USER_ACCOUNT_UPDATE("user.account.update", false),
    USER_ACCOUNT_CHANGE_ROLE("user.account.change_role", false),
    USER_ACCOUNT_SUSPEND("user.account.suspend", false),
    USER_ACCOUNT_DELETE("user.account.delete", false),
    WORKFLOW_RUN_VIEW("workflow.run.view", false),
    WORKFLOW_WORKFLOW_MANAGE("workflow.workflow.manage", false),
    SUPPORT_TICKET_VIEW("support.ticket.view", false),
    SUPPORT_TICKET_REPLY("support.ticket.reply", false),
    SUPPORT_TICKET_CLOSE("support.ticket.close", false),
    SUPPORT_TICKET_ASSIGN("support.ticket.assign", false),
    CONTENT_HELP_TOPIC_VIEW("content.help_topic.view", false),
    CONTENT_HELP_TOPIC_MANAGE("content.help_topic.manage", false),
    CONTENT_HELP_ARTICLE_VIEW("content.help_article.view", false),
    CONTENT_HELP_ARTICLE_MANAGE("content.help_article.manage", false),
    PLATFORM_SETTING_VIEW("platform.setting.view", false),
    PLATFORM_SETTING_UPDATE("platform.setting.update", false),
    PLATFORM_SMS_OUTBOX_VIEW("platform.sms_outbox.view", false),
    SYSTEM_ROLE_VIEW("system.role.view", true),
    SYSTEM_ADMIN_ACCOUNT_VIEW("system.admin_account.view", true),
    SYSTEM_ADMIN_ACCOUNT_MANAGE("system.admin_account.manage", true);

    private static final Map<String, Permission> BY_KEY =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(Permission::key, Function.identity()));

    private final String key;
    private final String module;
    private final String feature;
    private final String action;
    private final boolean superAdminOnly;

    Permission(String key, boolean superAdminOnly) {
        String[] parts = key.split("\\.", 3);
        this.key = key;
        this.module = parts[0];
        this.feature = parts[1];
        this.action = parts[2];
        this.superAdminOnly = superAdminOnly;
    }

    @JsonValue
    public String key() {
        return key;
    }

    public String module() {
        return module;
    }

    public String feature() {
        return feature;
    }

    public String action() {
        return action;
    }

    /** True when only the super admin may hold the key; no other role is ever granted it. */
    public boolean superAdminOnly() {
        return superAdminOnly;
    }

    public static Optional<Permission> fromKey(String key) {
        return Optional.ofNullable(key == null ? null : BY_KEY.get(key));
    }
}
