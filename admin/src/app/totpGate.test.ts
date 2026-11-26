import { totpGate } from './totpGate';

function me(enabled: boolean, required: boolean, verified: boolean) {
  return { totp: { enabled, required, verified } };
}

describe('totpGate', () => {
  it('sends an account that must enroll and has not to the setup screen', () => {
    expect(totpGate(me(false, true, false))).toBe('setup');
  });

  it('signs out an enrolled account whose session has not passed a code', () => {
    expect(totpGate(me(true, true, false))).toBe('signOut');
  });

  it('lets an enrolled account through once its session passed a code', () => {
    expect(totpGate(me(true, true, true))).toBe('enter');
  });

  it('lets an account through when the second factor is not required', () => {
    expect(totpGate(me(false, false, false))).toBe('enter');
    expect(totpGate(me(true, false, false))).toBe('enter');
  });
});
