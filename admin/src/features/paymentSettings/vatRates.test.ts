import { bpsToPercent, currentRateOf, percentToBps, vatToday } from './vatRates';

describe('percentToBps', () => {
  it.each([
    ['8', 800],
    ['10.00', 1000],
    ['8.5', 850],
    ['8,5', 850],
    [' 8 ', 800],
    ['0', 0],
    ['100', 10000],
  ])('reads %s as %i basis points', (input, bps) => {
    expect(percentToBps(input)).toBe(bps);
  });

  it.each(['8.555', '-1', '100.01', 'abc', '', '8.', '1e1'])('refuses %j', (input) => {
    expect(percentToBps(input)).toBeNull();
  });
});

describe('bpsToPercent', () => {
  it('shows two decimals and the sign', () => {
    expect(bpsToPercent(1000)).toBe('10.00%');
    expect(bpsToPercent(850)).toBe('8.50%');
  });
});

describe('currentRateOf', () => {
  const rates = [
    { id: 'a', rateBps: 1000, validFrom: '1900-01-01', validTo: '2026-07-01' },
    { id: 'b', rateBps: 800, validFrom: '2026-07-01', validTo: null },
  ];

  it('picks the row whose half-open range holds the day', () => {
    expect(currentRateOf(rates, '2026-12-31')?.id).toBe('b');
    expect(currentRateOf(rates, '2026-06-30')?.id).toBe('a');
    expect(currentRateOf(rates, '2026-07-01')?.id).toBe('b');
  });

  it('answers null when no row covers the day', () => {
    expect(currentRateOf([{ id: 'c', rateBps: 800, validFrom: '2027-01-01', validTo: null }], '2026-12-31')).toBeNull();
    expect(currentRateOf([], '2026-12-31')).toBeNull();
  });
});

describe('vatToday', () => {
  it('is the Vietnamese calendar day', () => {
    expect(vatToday(new Date('2026-12-31T17:00:00Z'))).toBe('2027-01-01');
    expect(vatToday(new Date('2026-12-31T16:59:59Z'))).toBe('2026-12-31');
  });
});
