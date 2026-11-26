import { can, type Caller } from '../../app/permissions';

export type ModerationAction = 'hide' | 'remove' | 'restore' | 'dismiss' | 'resolve';
export type ModerationStatus = 'visible' | 'hidden' | 'removed';

/** Actions that change the workflow's moderation status; the report routes require both keys for them. */
const STATUS_CHANGING: ReadonlySet<ModerationAction> = new Set(['hide', 'remove', 'restore']);

/** Actions that need a written reason before the request is sent. */
export function reasonRequired(action: ModerationAction): boolean {
  return STATUS_CHANGING.has(action);
}

function applicable(status: ModerationStatus, deleted: boolean): ModerationAction[] {
  if (deleted) return ['dismiss', 'resolve'];
  return status === 'visible'
    ? ['hide', 'remove', 'dismiss', 'resolve']
    : ['restore', 'dismiss', 'resolve'];
}

/**
 * The actions the detail page offers: those that apply to the workflow's state and whose route the
 * caller's keys open. A deleted workflow's status cannot change, so only closing the report remains.
 */
export function allowedActions(
  moderationStatus: ModerationStatus,
  deleted: boolean,
  me: Caller | undefined,
): ModerationAction[] {
  const mayClose = can(me, 'moderation.report.resolve');
  const mayChangeStatus = mayClose && can(me, 'moderation.content.hide');
  return applicable(moderationStatus, deleted).filter((action) =>
    STATUS_CHANGING.has(action) ? mayChangeStatus : mayClose,
  );
}
