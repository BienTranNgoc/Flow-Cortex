import { canDeleteRun, parse, serialize, toListParams, trayRefetchInterval } from './runHistoryRules';

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
