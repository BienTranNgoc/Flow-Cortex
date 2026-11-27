import { ApiError } from '@flow-cortex/api-client';
import { totpFailure, totpFailureFrom } from './totpFailure';

describe('totpFailure', () => {
  it('reads a 422 with the invalid-code code as a wrong code', () => {
    expect(totpFailure(422, { code: 'TOTP_INVALID_CODE' })).toEqual({ kind: 'invalid' });
  });

  it('reads a 423 as locked and carries the end of the lock', () => {
    const lockedUntil = '2026-11-26T08:15:00Z';

    expect(totpFailure(423, { code: 'TOTP_LOCKED', lockedUntil })).toEqual({
      kind: 'locked',
      lockedUntil: new Date(lockedUntil),
    });
  });

  it('reads a 423 without a readable end as locked with no end time', () => {
    expect(totpFailure(423, { code: 'TOTP_LOCKED' })).toEqual({
      kind: 'locked',
      lockedUntil: null,
    });
    expect(totpFailure(423, { code: 'TOTP_LOCKED', lockedUntil: 'soon' })).toEqual({
      kind: 'locked',
      lockedUntil: null,
    });
    expect(totpFailure(423, undefined)).toEqual({ kind: 'locked', lockedUntil: null });
  });

  it('reads a 401 with the challenge-invalid code as an expired sign-in step', () => {
    expect(totpFailure(401, { code: 'TOTP_CHALLENGE_INVALID' })).toEqual({ kind: 'expired' });
  });

  it('reads a 503 as an unreadable second factor', () => {
    expect(totpFailure(503, { code: 'TOTP_UNAVAILABLE' })).toEqual({ kind: 'unavailable' });
  });

  it('treats every other answer, and a right status with the wrong code, as unknown', () => {
    expect(totpFailure(500, undefined)).toEqual({ kind: 'unknown' });
    expect(totpFailure(401, { code: 'OTHER' })).toEqual({ kind: 'unknown' });
    expect(totpFailure(401, undefined)).toEqual({ kind: 'unknown' });
    expect(totpFailure(422, { code: 'OTHER' })).toEqual({ kind: 'unknown' });
    expect(totpFailure(409, { code: 'TOTP_NOT_ENABLED' })).toEqual({ kind: 'unknown' });
  });
});

describe('totpFailureFrom', () => {
  it('reads the status and problem of an API refusal', () => {
    const error = new ApiError(422, { code: 'TOTP_INVALID_CODE' });

    expect(totpFailureFrom(error)).toEqual({ kind: 'invalid' });
  });

  it('treats an error that is not an API refusal as unknown', () => {
    expect(totpFailureFrom(new TypeError('offline'))).toEqual({ kind: 'unknown' });
    expect(totpFailureFrom(null)).toEqual({ kind: 'unknown' });
  });
});
