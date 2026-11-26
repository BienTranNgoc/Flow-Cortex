import type { RunResultResponse } from '@flow-cortex/api-client';
import {
  canDeleteRun,
  overrideResults,
  parse,
  pendingRunIds,
  resultSummaryView,
  resultsRefetchInterval,
  serialize,
  settledRunIds,
  toListParams,
  trayRefetchInterval,
} from './runHistoryRules';

const WORKFLOW = '3f2b8c1e-5a4d-4e7b-9c10-0a1b2c3d4e5f';

function params(query: string) {
  return new URLSearchParams(query);
}

describe('run history filters in the address', () => {
  it('reads every filter and the page', () => {
    expect(
      parse(
        params(`status=error&workflowId=${WORKFLOW}&from=2026-11-02&to=2026-11-03&favorite=1&page=3`),
      ),
    ).toEqual({
      status: 'error',
      workflowId: WORKFLOW,
      from: '2026-11-02',
      to: '2026-11-03',
      favorite: true,
      page: 3,
    });
  });

  it('drops an unknown status and malformed ids, dates and pages', () => {
    expect(
      parse(params('status=done&workflowId=abc&from=2026-13-40&to=yesterday&favorite=yes&page=0')),
    ).toEqual({ favorite: false, page: 1 });
    expect(parse(params('page=-2'))).toEqual({ favorite: false, page: 1 });
    expect(parse(params('page=2.5'))).toEqual({ favorite: false, page: 1 });
    expect(parse(params('from=2026-02-30'))).toEqual({ favorite: false, page: 1 });
  });

  it('drops an end date before the start date', () => {
    expect(parse(params('from=2026-11-05&to=2026-11-02'))).toEqual({
      from: '2026-11-05',
      favorite: false,
      page: 1,
    });
  });

  it('round-trips through serialize and omits defaults', () => {
    const filters = parse(
      params(
        `status=waiting_approval&workflowId=${WORKFLOW}&from=2026-11-02&to=2026-11-03&favorite=1&page=2`,
      ),
    );
    expect(parse(serialize(filters))).toEqual(filters);
    expect(serialize({ favorite: false, page: 1 }).toString()).toBe('');
  });
});

describe('list parameters', () => {
  it('turns local dates into Ho Chi Minh day bounds', () => {
    const list = toListParams({ from: '2026-11-02', to: '2026-11-03', favorite: true, page: 3 }, 20);
    expect(list.from).toBe('2026-11-01T17:00:00Z');
    expect(list.to).toBe('2026-11-03T17:00:00Z');
    expect(list).toMatchObject({ favorite: true, page: 2, size: 20 });
  });

  it('leaves out what is not set', () => {
    expect(toListParams({ favorite: false, page: 1 }, 20)).toEqual({ page: 0, size: 20 });
  });
});

describe('canDeleteRun', () => {
  it.each(['completed', 'error', 'cancelled'] as const)('is true for %s', (status) => {
    expect(canDeleteRun(status)).toBe(true);
  });
  it.each(['running', 'waiting_approval', 'cancelling'] as const)('is false for %s', (status) => {
    expect(canDeleteRun(status)).toBe(false);
  });
});

describe('trayRefetchInterval', () => {
  const run = { id: 'r' } as never;
  it('is 15 seconds with a live run', () => {
    expect(trayRefetchInterval({ active: [run], recent: [] }, true)).toBe(15_000);
  });
  it('is 60 seconds otherwise', () => {
    expect(trayRefetchInterval({ active: [], recent: [run] }, true)).toBe(60_000);
    expect(trayRefetchInterval(undefined, true)).toBe(60_000);
  });
  it('stops while signed out', () => {
    expect(trayRefetchInterval({ active: [run], recent: [] }, false)).toBe(false);
  });
});

function result(patch: Partial<RunResultResponse> = {}): RunResultResponse {
  return { runId: 'r1', count: 0, kinds: [], thumbnails: [], registration: 'none', ...patch };
}

describe('resultSummaryView', () => {
  it('shows the count and thumbnails when files exist', () => {
    const thumbnails = [{ url: 'https://s/a' }, { url: 'https://s/b' }];
    expect(resultSummaryView(result({ count: 3, registration: 'done', thumbnails }))).toEqual({
      kind: 'files',
      count: 3,
      thumbnails: ['https://s/a', 'https://s/b'],
    });
    expect(resultSummaryView(result({ count: 2, registration: 'none' }))).toMatchObject({
      kind: 'files',
      count: 2,
    });
  });

  it('shows saving while the registration is pending, with no count', () => {
    expect(resultSummaryView(result({ registration: 'pending' }))).toEqual({ kind: 'saving' });
    expect(resultSummaryView(result({ count: 2, registration: 'pending' }))).toEqual({
      kind: 'saving',
    });
  });

  it('shows nothing for zero files, a missing summary or no registration', () => {
    expect(resultSummaryView(result({ registration: 'none' }))).toEqual({ kind: 'none' });
    expect(resultSummaryView(result({ registration: 'done' }))).toEqual({ kind: 'none' });
    expect(resultSummaryView(undefined)).toEqual({ kind: 'none' });
  });

  it('keeps at most three thumbnails', () => {
    const thumbnails = ['a', 'b', 'c', 'd', 'e'].map((url) => ({ url }));
    expect(resultSummaryView(result({ count: 5, registration: 'done', thumbnails }))).toEqual({
      kind: 'files',
      count: 5,
      thumbnails: ['a', 'b', 'c'],
    });
  });
});

describe('pendingRunIds and overrideResults', () => {
  const settled = result({ runId: 'a', count: 1, registration: 'done' });
  const saving = result({ runId: 'b', registration: 'pending' });

  it('lists only the runs whose registration is pending', () => {
    expect(pendingRunIds([settled, saving, result({ runId: 'c' })])).toEqual(['b']);
    expect(pendingRunIds([])).toEqual([]);
    expect(pendingRunIds(undefined)).toEqual([]);
  });

  it('replaces only the runs it was given and keeps the rest untouched, in order', () => {
    const fresh = result({ runId: 'b', count: 2, registration: 'done' });
    const merged = overrideResults([settled, saving], [fresh, result({ runId: 'zz' })]);

    expect(merged).toEqual([settled, fresh]);
    expect(merged[0]).toBe(settled);
  });
});

describe('settledRunIds', () => {
  const before = new Map<string, 'running' | 'waiting_approval' | 'completed'>([
    ['live', 'running'],
    ['gate', 'waiting_approval'],
    ['done', 'completed'],
  ]);

  it('lists the runs that went from unfinished to finished', () => {
    const now = [
      { id: 'live', status: 'completed' as const },
      { id: 'gate', status: 'error' as const },
      { id: 'done', status: 'completed' as const },
    ];
    expect(settledRunIds(before, now)).toEqual(['live', 'gate']);
  });

  it('ignores runs still unfinished and runs not seen before', () => {
    const now = [
      { id: 'live', status: 'waiting_approval' as const },
      { id: 'new', status: 'completed' as const },
    ];
    expect(settledRunIds(before, now)).toEqual([]);
    expect(settledRunIds(new Map(), now)).toEqual([]);
  });
});

describe('resultsRefetchInterval', () => {
  const pending = result({ registration: 'pending' });
  const done = result({ count: 1, registration: 'done' });

  it('refetches every five seconds while any result is pending', () => {
    expect(resultsRefetchInterval([done, pending], 1)).toBe(5_000);
    expect(resultsRefetchInterval([done, pending], 23)).toBe(5_000);
  });

  it('stops when nothing is pending, nothing has loaded, or after 24 polls', () => {
    expect(resultsRefetchInterval([done, result()], 1)).toBe(false);
    expect(resultsRefetchInterval([], 1)).toBe(false);
    expect(resultsRefetchInterval(undefined, 0)).toBe(false);
    expect(resultsRefetchInterval([pending], 24)).toBe(false);
    expect(resultsRefetchInterval([pending], 40)).toBe(false);
  });
});
