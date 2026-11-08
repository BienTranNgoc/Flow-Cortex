import { ApiError } from '@flow-cortex/api-client';
import { supportProblemCode } from './useSupportKb';

describe('supportProblemCode', () => {
  it('reads the code member', () => {
    expect(supportProblemCode(new ApiError(409, { status: 409, code: 'request-closed' }))).toBe(
      'request-closed',
    );
  });

  it('falls back to the last segment of a Flow-Cortex problem type when the code is absent', () => {
    const validation = new ApiError(400, {
      status: 400,
      type: 'https://flow-cortex.dev/problems/validation-failed',
      errors: { subject: 'size must be between 5 and 120' },
    });

    expect(supportProblemCode(validation)).toBe('validation-failed');
  });

  it.each([
    ['a foreign type', { status: 400, type: 'about:blank' }],
    ['no type at all', { status: 500 }],
  ])('gives nothing for %s', (_case, problem) => {
    expect(supportProblemCode(new ApiError(problem.status, problem))).toBeUndefined();
  });

  it('gives nothing for an error that is not an API error', () => {
    expect(supportProblemCode(new Error('offline'))).toBeUndefined();
  });
});
