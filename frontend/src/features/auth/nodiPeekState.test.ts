import { nodiPeekState } from './nodiPeekState';

const base = { focused: null, pending: false, failed: false, succeeded: false } as const;

describe('nodiPeekState', () => {
  it('is happy once signed in, whatever else holds', () => {
    expect(nodiPeekState({ ...base, succeeded: true, failed: true, focused: 'password' })).toBe(
      'happy',
    );
  });

  it('is happy while signing in', () => {
    expect(nodiPeekState({ ...base, pending: true, failed: true, focused: 'password' })).toBe(
      'happy',
    );
  });

  it('is worried after a failure even with the password field focused', () => {
    expect(nodiPeekState({ ...base, failed: true, focused: 'password' })).toBe('worried');
  });

  it('covers its eyes while the password field is focused', () => {
    expect(nodiPeekState({ ...base, focused: 'password' })).toBe('shy');
  });

  it('looks at the email field while it is focused', () => {
    expect(nodiPeekState({ ...base, focused: 'email' })).toBe('look');
  });

  it('is idle otherwise', () => {
    expect(nodiPeekState(base)).toBe('idle');
  });
});
