import { SAMPLE_INPUT_MAX, sampleInputFrom } from './sampleHandoff';

describe('sampleInputFrom', () => {
  it('takes a string input', () => {
    expect(sampleInputFrom({ sampleInput: 'Summarise this week' })).toBe('Summarise this week');
  });

  it('ignores an input over 10,000 characters', () => {
    expect(sampleInputFrom({ sampleInput: 'a'.repeat(SAMPLE_INPUT_MAX + 1) })).toBe('');
    expect(sampleInputFrom({ sampleInput: 'a'.repeat(SAMPLE_INPUT_MAX) })).toHaveLength(
      SAMPLE_INPUT_MAX,
    );
  });

  it('ignores a non-string or missing state', () => {
    expect(sampleInputFrom(null)).toBe('');
    expect(sampleInputFrom(undefined)).toBe('');
    expect(sampleInputFrom('text')).toBe('');
    expect(sampleInputFrom({})).toBe('');
    expect(sampleInputFrom({ sampleInput: 42 })).toBe('');
  });
});
