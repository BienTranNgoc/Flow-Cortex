import type { Caller } from '../../app/permissions';
import { canStopRun } from './canStopRun';

const OPERATOR: Caller = { permissions: ['workflow.run.cancel', 'workflow.run.view'], adminDisabled: false };

describe('canStopRun', () => {
  it.each(['running', 'waiting_approval'])('is true for a %s run when the key is held', (status) => {
    expect(canStopRun(status, OPERATOR)).toBe(true);
  });

  it.each(['completed', 'error', 'cancelling', 'cancelled', 'paused'])(
    'is false for a %s run',
    (status) => {
      expect(canStopRun(status, OPERATOR)).toBe(false);
    },
  );

  it('is false without the key', () => {
    expect(canStopRun('running', { permissions: ['workflow.run.view'], adminDisabled: false })).toBe(false);
  });

  it('is false for a deactivated admin and before the caller is known', () => {
    expect(canStopRun('running', { ...OPERATOR, adminDisabled: true })).toBe(false);
    expect(canStopRun('running', undefined)).toBe(false);
  });
});
