import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { dayBoundaries, groupConversations, timeGroupOf } from './sessionGroups';

// Vitest runs this file in a worker thread, where a TZ change after start-up is ignored, so the
// zone-dependent cases run the module in a child Node process that starts with the zone set.
function boundariesIn(timeZone: string, year: number, monthIndex: number, day: number) {
  const moduleUrl = pathToFileURL(resolve(process.cwd(), 'src/features/chat/history/sessionGroups.ts')).href;
  const script = `const m = await import(${JSON.stringify(moduleUrl)});
    const b = m.dayBoundaries(new Date(${year}, ${monthIndex}, ${day}, 12, 0));
    console.log(JSON.stringify({ today: b.todayStart.toISOString(), week: b.weekStart.toISOString() }));`;
  const out = execFileSync(
    process.execPath,
    ['--experimental-strip-types', '--no-warnings', '--input-type=module', '-e', script],
    { env: { ...process.env, TZ: timeZone }, encoding: 'utf8' },
  );
  return JSON.parse(out) as { today: string; week: string };
}

describe('dayBoundaries', () => {
  it('gives local midnight and the local midnight 7 calendar days before it', () => {
    expect(boundariesIn('Asia/Ho_Chi_Minh', 2026, 10, 8)).toEqual({
      today: '2026-11-07T17:00:00.000Z',
      week: '2026-10-31T17:00:00.000Z',
    });
  });

  it('keeps local midnight when a daylight-saving change falls inside the window', () => {
    // New York's clocks go back on the first Sunday of November, so 7 days of 24 h before today's midnight is 01:00.
    expect(boundariesIn('America/New_York', 2026, 10, 5)).toEqual({
      today: '2026-11-05T05:00:00.000Z',
      week: '2026-10-29T04:00:00.000Z',
    });
  });

  it('returns Date instants for the current zone', () => {
    const { todayStart, weekStart } = dayBoundaries(new Date(2026, 10, 8, 15, 0));
    expect(todayStart).toEqual(new Date(2026, 10, 8));
    expect(weekStart).toEqual(new Date(2026, 10, 1));
  });
});

describe('timeGroupOf', () => {
  const boundaries = {
    todayStart: new Date('2026-11-08T00:00:00.000Z'),
    weekStart: new Date('2026-11-01T00:00:00.000Z'),
  };

  it('puts an instant equal to todayStart in today', () => {
    expect(timeGroupOf('2026-11-08T00:00:00.000Z', boundaries)).toBe('today');
    expect(timeGroupOf('2026-11-08T10:00:00.000Z', boundaries)).toBe('today');
  });

  it('puts an instant equal to weekStart in last7 and just before todayStart in last7', () => {
    expect(timeGroupOf('2026-11-01T00:00:00.000Z', boundaries)).toBe('last7');
    expect(timeGroupOf('2026-11-07T23:59:59.999Z', boundaries)).toBe('last7');
  });

  it('puts anything earlier in earlier', () => {
    expect(timeGroupOf('2026-10-31T23:59:59.999Z', boundaries)).toBe('earlier');
  });
});

describe('groupConversations', () => {
  const boundaries = {
    todayStart: new Date('2026-11-08T00:00:00.000Z'),
    weekStart: new Date('2026-11-01T00:00:00.000Z'),
  };
  const row = (id: string, lastActivityAt: string) => ({ id, lastActivityAt });
  const a = row('a', '2026-11-08T09:00:00.000Z');
  const b = row('b', '2026-11-03T09:00:00.000Z');
  const c = row('c', '2026-11-08T08:00:00.000Z');
  const d = row('d', '2026-09-01T09:00:00.000Z');
  const rows = [a, b, c, d];

  it('keeps the given order inside each group and lists today first for newest', () => {
    const groups = groupConversations(rows, boundaries, 'newest');
    expect(groups.map((g) => g.group)).toEqual(['today', 'last7', 'earlier']);
    expect(groups[0]?.items.map((r) => r.id)).toEqual(['a', 'c']);
  });

  it('drops empty groups', () => {
    const groups = groupConversations([a, d], boundaries, 'newest');
    expect(groups.map((g) => g.group)).toEqual(['today', 'earlier']);
    expect(groupConversations([], boundaries, 'newest')).toEqual([]);
  });

  it('reverses the group order for oldest without touching the row order', () => {
    const groups = groupConversations(rows, boundaries, 'oldest');
    expect(groups.map((g) => g.group)).toEqual(['earlier', 'last7', 'today']);
    expect(groups[2]?.items.map((r) => r.id)).toEqual(['a', 'c']);
  });
});
