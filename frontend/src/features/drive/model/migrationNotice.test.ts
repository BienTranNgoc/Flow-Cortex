import { dismissNotice, noticeVisible } from './migrationNotice';

const RECORD = { migratedAt: '2026-11-20T10:00:00Z' };

function memoryStorage(initial: Record<string, string> = {}) {
  const values = new Map(Object.entries(initial));
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => {
      values.set(key, value);
    },
    values,
  };
}

const blockedStorage = {
  getItem: () => {
    throw new DOMException('blocked', 'SecurityError');
  },
  setItem: () => {
    throw new DOMException('blocked', 'SecurityError');
  },
};

describe('migrationNotice', () => {
  it('shows the notice when migratedAt is set and nothing is stored', () => {
    expect(noticeVisible(RECORD, 'user-a', memoryStorage())).toBe(true);
  });

  it('hides it once that member dismissed that migratedAt', () => {
    const storage = memoryStorage();

    dismissNotice(RECORD, 'user-a', storage);

    expect(noticeVisible(RECORD, 'user-a', storage)).toBe(false);
    expect(storage.values.get('fc.drive.migrationNotice.user-a')).toBe(RECORD.migratedAt);
  });

  it('shows a newer migratedAt after an older one was dismissed', () => {
    const storage = memoryStorage();
    dismissNotice(RECORD, 'user-a', storage);

    expect(noticeVisible({ migratedAt: '2026-12-01T00:00:00Z' }, 'user-a', storage)).toBe(true);
  });

  it("shows another member's notice on the same storage", () => {
    const storage = memoryStorage();
    dismissNotice(RECORD, 'user-a', storage);

    expect(noticeVisible(RECORD, 'user-b', storage)).toBe(true);
  });

  it('hides nothing and throws nothing when storage throws', () => {
    expect(noticeVisible(RECORD, 'user-a', blockedStorage)).toBe(true);
    expect(() => dismissNotice(RECORD, 'user-a', blockedStorage)).not.toThrow();
    expect(noticeVisible(RECORD, 'user-a', blockedStorage)).toBe(true);
  });

  it('shows nothing when migratedAt is null', () => {
    expect(noticeVisible({ migratedAt: null }, 'user-a', memoryStorage())).toBe(false);
  });
});
