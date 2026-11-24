import { formatBytes } from './formatBytes';

describe('formatBytes', () => {
  it('1023 bytes stay bytes', () => {
    expect(formatBytes(0, 'en')).toBe('0 B');
    expect(formatBytes(1023, 'en')).toBe('1,023 B');
  });

  it('1,048,576 bytes are 1 MB', () => {
    expect(formatBytes(1_048_576, 'en')).toBe('1 MB');
  });

  it('52,428,800 bytes are 50 MB', () => {
    expect(formatBytes(52_428_800, 'en')).toBe('50 MB');
  });

  it('uses binary steps and one decimal', () => {
    expect(formatBytes(1024, 'en')).toBe('1 KB');
    expect(formatBytes(1536, 'en')).toBe('1.5 KB');
    expect(formatBytes(1_073_741_824, 'en')).toBe('1 GB');
    expect(formatBytes(5 * 1_073_741_824 * 1024, 'en')).toBe('5,120 GB');
  });

  it('writes the decimal mark of the locale', () => {
    expect(formatBytes(1536, 'vi')).toBe('1,5 KB');
  });
});
