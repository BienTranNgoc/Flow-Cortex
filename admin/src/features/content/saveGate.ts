export interface SaveGateState {
  canManage: boolean;
  pending: boolean;
  /** The editor holds changes the server has not stored. */
  unsaved: boolean;
  /** The stored draft's version: 0 means no draft was ever saved. */
  version: number;
}

/**
 * The one arbiter of whether Save may act: saving unchanged content stores a duplicate snapshot
 * and bumps the version under a co-editor. The first save of a document with no draft is allowed.
 */
export function saveGate({ canManage, pending, unsaved, version }: SaveGateState): boolean {
  return canManage && !pending && (unsaved || version < 1);
}
