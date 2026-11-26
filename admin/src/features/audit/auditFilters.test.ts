import {
  EMPTY_AUDIT_FILTERS,
  isFiltered,
  rangeIsReversed,
  sendableSearch,
  toRequestParams,
} from './auditFilters';

describe('sendableSearch', () => {
  it('holds back text shorter than three characters and trims the rest', () => {
    expect(sendableSearch('ab')).toBe('');
    expect(sendableSearch('  ab  ')).toBe('');
    expect(sendableSearch('  mai ')).toBe('mai');
  });

  it('cuts the text to the length the server accepts', () => {
    expect(sendableSearch('x'.repeat(150))).toHaveLength(100);
  });
});

describe('toRequestParams', () => {
  it('leaves empty filters out', () => {
    expect(toRequestParams(EMPTY_AUDIT_FILTERS)).toEqual({});
  });

  it('turns the chosen days into UTC instants: the start of the first day and the start of the day after the last', () => {
    expect(
      toRequestParams({
        ...EMPTY_AUDIT_FILTERS,
        status: 'failed',
        fromDate: '2026-11-15',
        toDate: '2026-11-15',
      }),
    ).toEqual({
      status: 'failed',
      from: '2026-11-15T00:00:00.000Z',
      to: '2026-11-16T00:00:00.000Z',
    });
  });

  it('rolls a month end over to the next month', () => {
    expect(toRequestParams({ ...EMPTY_AUDIT_FILTERS, toDate: '2026-11-30' }).to).toBe(
      '2026-12-01T00:00:00.000Z',
    );
  });

  it('ignores a day that is not a date', () => {
    expect(toRequestParams({ ...EMPTY_AUDIT_FILTERS, fromDate: '2026-13-45' })).toEqual({});
  });
});

describe('rangeIsReversed', () => {
  it('is true only when the first day is after the last', () => {
    expect(rangeIsReversed({ fromDate: '2026-11-16', toDate: '2026-11-15' })).toBe(true);
    expect(rangeIsReversed({ fromDate: '2026-11-15', toDate: '2026-11-15' })).toBe(false);
    expect(rangeIsReversed({ fromDate: '2026-11-15', toDate: '' })).toBe(false);
  });
});

describe('isFiltered', () => {
  it('is false for the empty filters and true once any one is set', () => {
    expect(isFiltered(EMPTY_AUDIT_FILTERS)).toBe(false);
    expect(isFiltered({ ...EMPTY_AUDIT_FILTERS, actorUserId: 'u1' })).toBe(true);
  });
});
