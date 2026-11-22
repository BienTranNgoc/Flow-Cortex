import { can, type Caller } from '../../app/permissions';

/** Why Publish is unavailable; the editor turns each into its hint. */
export type PublishGateReason = 'forbidden' | 'unsaved' | 'no-draft';

export interface PublishGateState {
  me: Caller | undefined;
  /** The editor holds changes the server has not stored. */
  unsaved: boolean;
  /** The stored draft's version: 0 means no draft was ever saved. */
  version: number;
}

export interface PublishGate {
  enabled: boolean;
  reason: PublishGateReason | null;
}

/** The one arbiter of whether Publish may be pressed: permission first, then unsaved text, then a stored draft. */
export function publishGate({ me, unsaved, version }: PublishGateState): PublishGate {
  if (!can(me, 'content.document.manage')) return { enabled: false, reason: 'forbidden' };
  if (unsaved) return { enabled: false, reason: 'unsaved' };
  if (version < 1) return { enabled: false, reason: 'no-draft' };
  return { enabled: true, reason: null };
}
