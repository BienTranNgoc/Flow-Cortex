import type { TFunction } from 'i18next';
import type { PermissionKey } from '../../app/permissions';

/**
 * One label key per catalog key: the compiler fails here when the generated `PermissionKey` union
 * gains a key that has no label.
 */
export const ACTION_LABEL_KEYS = {
  'user.account.view': 'roles.catalog.actions.user.account.view',
  'user.account.create': 'roles.catalog.actions.user.account.create',
  'user.account.update': 'roles.catalog.actions.user.account.update',
  'user.account.change_role': 'roles.catalog.actions.user.account.change_role',
  'user.account.suspend': 'roles.catalog.actions.user.account.suspend',
  'user.account.delete': 'roles.catalog.actions.user.account.delete',
  'workflow.run.view': 'roles.catalog.actions.workflow.run.view',
  'workflow.workflow.manage': 'roles.catalog.actions.workflow.workflow.manage',
  'support.ticket.view': 'roles.catalog.actions.support.ticket.view',
  'support.ticket.reply': 'roles.catalog.actions.support.ticket.reply',
  'support.ticket.close': 'roles.catalog.actions.support.ticket.close',
  'support.ticket.assign': 'roles.catalog.actions.support.ticket.assign',
  'content.help_topic.view': 'roles.catalog.actions.content.help_topic.view',
  'content.help_topic.manage': 'roles.catalog.actions.content.help_topic.manage',
  'content.help_article.view': 'roles.catalog.actions.content.help_article.view',
  'content.help_article.manage': 'roles.catalog.actions.content.help_article.manage',
  'content.document.view': 'roles.catalog.actions.content.document.view',
  'content.document.manage': 'roles.catalog.actions.content.document.manage',
  'content.media.view': 'roles.catalog.actions.content.media.view',
  'content.media.manage': 'roles.catalog.actions.content.media.manage',
  'platform.setting.view': 'roles.catalog.actions.platform.setting.view',
  'platform.setting.update': 'roles.catalog.actions.platform.setting.update',
  'platform.sms_outbox.view': 'roles.catalog.actions.platform.sms_outbox.view',
  'system.role.view': 'roles.catalog.actions.system.role.view',
  'system.role.manage': 'roles.catalog.actions.system.role.manage',
  'system.admin_account.view': 'roles.catalog.actions.system.admin_account.view',
  'system.admin_account.manage': 'roles.catalog.actions.system.admin_account.manage',
  'ai.provider.view': 'roles.catalog.actions.ai.provider.view',
  'ai.provider.update': 'roles.catalog.actions.ai.provider.update',
  'workflow.run.export': 'roles.catalog.actions.workflow.run.export',
  'workflow.run.cancel': 'roles.catalog.actions.workflow.run.cancel',
  'workflow.workflow.view': 'roles.catalog.actions.workflow.workflow.view',
  'workflow.workflow.export': 'roles.catalog.actions.workflow.workflow.export',
  'payment.setting.view': 'roles.catalog.actions.payment.setting.view',
  'payment.setting.update': 'roles.catalog.actions.payment.setting.update',
  'user.account.export': 'roles.catalog.actions.user.account.export',
  'moderation.report.view': 'roles.catalog.actions.moderation.report.view',
  'moderation.report.resolve': 'roles.catalog.actions.moderation.report.resolve',
  'moderation.content.hide': 'roles.catalog.actions.moderation.content.hide',
} satisfies Record<PermissionKey, string>;

const ACTION_KEYS: Readonly<Record<string, string>> = ACTION_LABEL_KEYS;

interface CatalogLabelled {
  readonly key: string;
  readonly module: string;
  readonly feature: string;
  readonly action: string;
}

/** A key the console has no label for (added by a newer server) is shown as the server sent it. */
export function actionLabel(t: TFunction, item: CatalogLabelled): string {
  const labelKey = ACTION_KEYS[item.key];
  return labelKey ? t(labelKey) : item.action;
}

export function featureLabel(t: TFunction, module: string, feature: string): string {
  return t(`roles.catalog.features.${module}.${feature}`, { defaultValue: feature });
}

export function moduleLabel(t: TFunction, module: string): string {
  return t(`roles.catalog.modules.${module}`, { defaultValue: module });
}
