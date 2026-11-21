import { stepUpFailure } from './stepUpFailure';

describe('stepUpFailure', () => {
  it('reads a 422 with the invalid-password code as a wrong password', () => {
    expect(stepUpFailure(422, { code: 'STEP_UP_INVALID_PASSWORD' }, null)).toEqual({
      kind: 'invalidPassword',
    });
  });

  it('reads a 409 with the password-required code as an account without a password', () => {
    expect(stepUpFailure(409, { code: 'STEP_UP_PASSWORD_REQUIRED' }, null)).toEqual({
      kind: 'passwordRequired',
    });
  });

  it('takes the wait of a 429 from the Retry-After header', () => {
    expect(stepUpFailure(429, { code: 'STEP_UP_RATE_LIMITED' }, '42')).toEqual({
      kind: 'rateLimited',
      seconds: 42,
    });
  });

  it('falls back to 60 seconds when a 429 carries no readable wait', () => {
    expect(stepUpFailure(429, undefined, null)).toEqual({ kind: 'rateLimited', seconds: 60 });
    expect(stepUpFailure(429, undefined, 'soon')).toEqual({ kind: 'rateLimited', seconds: 60 });
  });

  it('treats every other answer, and a right status with the wrong code, as unknown', () => {
    expect(stepUpFailure(500, undefined, null)).toEqual({ kind: 'unknown' });
    expect(stepUpFailure(403, { code: 'STEP_UP_REQUIRED' }, null)).toEqual({ kind: 'unknown' });
    expect(stepUpFailure(422, { code: 'OTHER' }, null)).toEqual({ kind: 'unknown' });
    expect(stepUpFailure(409, undefined, null)).toEqual({ kind: 'unknown' });
  });
});
