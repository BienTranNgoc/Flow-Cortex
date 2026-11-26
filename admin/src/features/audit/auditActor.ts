import type { AuditLogItem } from '@flow-cortex/api-client';

type ActorFields = Pick<AuditLogItem, 'actorName' | 'actorEmail' | 'actorUserId'>;

/** The actor as the trail recorded them: the name, else the email, else the account id. */
export function actorName(item: ActorFields): string | undefined {
  return item.actorName?.trim() || item.actorEmail?.trim() || item.actorUserId || undefined;
}
