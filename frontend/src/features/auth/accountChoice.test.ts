import { accountChoiceToApply } from './accountChoice';

const THEMES = ['light', 'dark'] as const;

describe('accountChoiceToApply', () => {
  it('applies nothing when the account has no value', () => {
    expect(accountChoiceToApply(null, 'light', THEMES)).toBeUndefined();
    expect(accountChoiceToApply(undefined, 'light', THEMES)).toBeUndefined();
  });

  it('applies nothing when the account agrees with the device', () => {
    expect(accountChoiceToApply('dark', 'dark', THEMES)).toBeUndefined();
  });

  it('applies the account value when it differs from the device', () => {
    expect(accountChoiceToApply('dark', 'light', THEMES)).toBe('dark');
  });

  it('applies nothing for a value this build does not know', () => {
    expect(accountChoiceToApply('sepia', 'light', THEMES)).toBeUndefined();
  });
});
