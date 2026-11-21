import { recoveryFor } from './errorRecovery';

describe('recoveryFor', () => {
  it('offers a retry for the first failure of a page that can retry', () => {
    expect(recoveryFor({ variant: 'page', failedRetries: 0, canRetry: true, silent: false })).toBe('retry');
  });

  it('offers a reload once a retry has failed', () => {
    expect(recoveryFor({ variant: 'page', failedRetries: 1, canRetry: true, silent: false })).toBe('reload');
  });

  it('offers a reload when the page cannot retry', () => {
    expect(recoveryFor({ variant: 'page', failedRetries: 0, canRetry: false, silent: false })).toBe('reload');
  });

  it('offers a reload for the whole-app view even when a retry is possible', () => {
    expect(recoveryFor({ variant: 'root', failedRetries: 0, canRetry: true, silent: false })).toBe('reload');
  });

  it('stays silent whatever the variant', () => {
    expect(recoveryFor({ variant: 'page', failedRetries: 0, canRetry: true, silent: true })).toBe('silent');
    expect(recoveryFor({ variant: 'root', failedRetries: 3, canRetry: false, silent: true })).toBe('silent');
  });
});
