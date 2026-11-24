export interface MigrationRecord {
  migratedAt: string | null;
}

export type NoticeStorage = Pick<Storage, 'getItem' | 'setItem'>;

const keyOf = (userId: string) => `fc.drive.migrationNotice.${userId}`;

/** True while `record` has a migration this member has not dismissed on this device. */
export function noticeVisible(record: MigrationRecord, userId: string, storage: NoticeStorage): boolean {
  if (!record.migratedAt) {
    return false;
  }
  try {
    return storage.getItem(keyOf(userId)) !== record.migratedAt;
  } catch {
    // Blocked storage cannot say the notice was dismissed, so it stays visible.
    return true;
  }
}

/** Remembers the dismissed `migratedAt`; a blocked storage leaves the caller to hide the notice for the visit. */
export function dismissNotice(record: MigrationRecord, userId: string, storage: NoticeStorage): void {
  if (!record.migratedAt) {
    return;
  }
  try {
    storage.setItem(keyOf(userId), record.migratedAt);
  } catch {
    // Nothing to persist to; see above.
  }
}
