import { formatDateTime, formatNumber } from './format';

describe('format', () => {
  it('groups thousands by the language convention', () => {
    expect(formatNumber(1234, 'vi')).toBe('1.234');
    expect(formatNumber(1234, 'en')).toBe('1,234');
  });

  it('orders the date by the language convention', () => {
    const value = '2026-10-18T03:04:05Z';
    const options = { timeZone: 'UTC', day: '2-digit', month: '2-digit', year: 'numeric' } as const;

    expect(formatDateTime(value, 'vi', options)).toBe('18/10/2026');
    expect(formatDateTime(value, 'en', options)).toBe('10/18/2026');
  });
});
