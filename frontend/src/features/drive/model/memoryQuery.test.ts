// The wire values mirror MemoryFilter of the backend (Sort, SizeBucket, time windows, content types).
// Expected instants are computed in the test, so it holds in any time zone.
import {
  initialMemoryQuery,
  memoryQueryReducer,
  SIZES,
  SORTS,
  TIMES,
  toParams,
  TYPES,
  type MemoryQuery,
  type MemoryQueryAction,
} from './memoryQuery';

function paramsOf(state: MemoryQuery) {
  const result = toParams(state);
  if ('error' in result) {
    throw new Error(`expected params, got ${result.error}`);
  }
  return result.params;
}

const customRange = (from: string, to: string): MemoryQuery => ({
  ...initialMemoryQuery,
  time: 'custom',
  from,
  to,
});

describe('memoryQuery', () => {
  it('keeps the wire values the server accepts', () => {
    expect(SORTS).toEqual(['newest', 'oldest', 'size_desc', 'size_asc']);
    expect(TIMES).toEqual(['all', '7d', '30d', 'custom']);
    expect(SIZES).toEqual(['lt1mb', '1to50mb', 'gt50mb']);
    expect(TYPES).toEqual(['text', 'image', 'video', 'audio']);
  });

  it('any change but page resets page to 0', () => {
    const onPageThree = { ...initialMemoryQuery, page: 2 };
    const changes: MemoryQueryAction[] = [
      { type: 'search', q: 'launch' },
      { type: 'filters', types: ['video'], time: '7d', from: '', to: '', sizes: ['gt50mb'] },
      { type: 'sort', sort: 'oldest' },
      { type: 'pageSize', pageSize: 50 },
      { type: 'clear' },
    ];

    for (const change of changes) {
      expect(memoryQueryReducer(onPageThree, change).page).toBe(0);
    }
    expect(memoryQueryReducer(onPageThree, { type: 'page', page: 4 }).page).toBe(4);
  });

  it('drops time all, empty arrays and empty q from the params', () => {
    expect(paramsOf(initialMemoryQuery)).toEqual({ sort: 'newest', page: 0, pageSize: 20 });
    expect(paramsOf({ ...initialMemoryQuery, q: '   ' })).toEqual({ sort: 'newest', page: 0, pageSize: 20 });
    expect(paramsOf({ ...initialMemoryQuery, q: ' launch ' }).q).toBe('launch');
    expect(paramsOf({ ...initialMemoryQuery, time: '30d' }).time).toBe('30d');
  });

  it('filters from page 3 return to the first page with the new filter', () => {
    const next = memoryQueryReducer(
      { ...initialMemoryQuery, page: 2 },
      { type: 'filters', types: ['video'], time: 'all', from: '', to: '', sizes: [] },
    );

    expect(paramsOf(next)).toEqual({ type: ['video'], sort: 'newest', page: 0, pageSize: 20 });
  });

  it('sends several types and sizes as arrays', () => {
    const params = paramsOf({ ...initialMemoryQuery, types: ['image', 'audio'], sizes: ['1to50mb'] });

    expect(params.type).toEqual(['image', 'audio']);
    expect(params.size).toEqual(['1to50mb']);
  });

  it('maps largest to size_desc and smallest to size_asc', () => {
    const largest = memoryQueryReducer(initialMemoryQuery, { type: 'sort', sort: 'size_desc' });
    const smallest = memoryQueryReducer(largest, { type: 'sort', sort: 'size_asc' });

    expect(paramsOf(largest).sort).toBe('size_desc');
    expect(paramsOf(smallest).sort).toBe('size_asc');
  });

  it("sends a custom day from the start to the end of that local day", () => {
    const params = paramsOf(customRange('2026-11-10', '2026-11-10'));

    expect(params.time).toBe('custom');
    expect(params.from).toBe(new Date(2026, 10, 10).toISOString());
    expect(params.to).toBe(new Date(2026, 10, 10, 23, 59, 59, 999).toISOString());
  });

  it('sends a from-only custom range without to', () => {
    const params = paramsOf(customRange('2026-11-10', ''));

    expect(params.time).toBe('custom');
    expect(params.from).toBe(new Date(2026, 10, 10).toISOString());
    expect('to' in params).toBe(false);
  });

  it('sends a to-only custom range without from', () => {
    const params = paramsOf(customRange('', '2026-11-10'));

    expect(params.to).toBe(new Date(2026, 10, 10, 23, 59, 59, 999).toISOString());
    expect('from' in params).toBe(false);
  });

  it('ignores the dates when the time is not custom', () => {
    const params = paramsOf({ ...customRange('2026-11-10', '2026-11-12'), time: '7d' });

    expect(params.time).toBe('7d');
    expect('from' in params).toBe(false);
    expect('to' in params).toBe(false);
  });

  it('refuses a reversed range', () => {
    expect(toParams(customRange('2026-11-20', '2026-11-10'))).toEqual({ error: 'range' });
  });

  it('refuses a custom range with neither date', () => {
    expect(toParams(customRange('', ''))).toEqual({ error: 'range' });
  });

  it('clears search and filters but keeps sort and page size', () => {
    const busy: MemoryQuery = {
      q: 'zzz',
      types: ['text'],
      time: 'custom',
      from: '2026-11-10',
      to: '',
      sizes: ['lt1mb'],
      sort: 'size_desc',
      page: 3,
      pageSize: 50,
    };

    expect(memoryQueryReducer(busy, { type: 'clear' })).toEqual({
      ...initialMemoryQuery,
      sort: 'size_desc',
      pageSize: 50,
    });
  });
});
