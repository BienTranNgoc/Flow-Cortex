import { ApiError } from '@flow-cortex/api-client';
import { totpFailure } from './totpFailure';

function failure(status: number, code?: string, extra: Record<string, unknown> = {}) {
  return totpFailure(new ApiError(status, { status, ...(code ? { code } : {}), ...extra }));
}

describe('totpFailure', () => {
  it('reads a wrong code as invalid', () => {
    expect(failure(422, 'TOTP_INVALID_CODE')).toEqual({ kind: 'invalid' });
  });

  it('reads a lock with the instant it ends', () => {
    expect(failure(423, 'TOTP_LOCKED', { lockedUntil: '2026-11-26T10:15:00Z' })).toEqual({
      kind: 'locked',
      lockedUntil: '2026-11-26T10:15:00Z',
    });
  });

  it('reads a dead challenge as expired', () => {
    expect(failure(401, 'TOTP_CHALLENGE_INVALID')).toEqual({ kind: 'expired' });
  });

  it('reads an unreadable secret as unavailable', () => {
    expect(failure(503, 'TOTP_UNAVAILABLE')).toEqual({ kind: 'unavailable' });
  });

  it('reads a setup that is gone as one to restart and an enabled account as already done', () => {
    expect(failure(409, 'TOTP_NOT_SET_UP')).toEqual({ kind: 'restart' });
    expect(failure(409, 'TOTP_ALREADY_ENABLED')).toEqual({ kind: 'alreadyEnabled' });
  });

  it('keeps any other problem generic, with the server detail when there is one', () => {
    expect(failure(500)).toEqual({ kind: 'other', detail: undefined });
    expect(totpFailure(new ApiError(500, { status: 500, detail: 'boom' }))).toEqual({
      kind: 'other',
      detail: 'boom',
    });
    expect(totpFailure(new TypeError('Failed to fetch'))).toEqual({
      kind: 'other',
      detail: undefined,
    });
  });
});
