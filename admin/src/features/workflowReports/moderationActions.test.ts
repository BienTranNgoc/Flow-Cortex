import type { Caller, PermissionKey } from '../../app/permissions';
import { allowedActions } from './moderationActions';

function caller(permissions: PermissionKey[], adminDisabled = false): Caller {
  return { permissions, adminDisabled };
}

const EVERY_KEY = caller(['moderation.report.view', 'moderation.report.resolve', 'moderation.content.hide']);

describe('allowedActions', () => {
  it('offers hide, remove, dismiss and resolve for a visible workflow', () => {
    expect(allowedActions('visible', false, EVERY_KEY)).toEqual(['hide', 'remove', 'dismiss', 'resolve']);
  });

  it.each(['hidden', 'removed'] as const)('offers restore, dismiss and resolve for a %s workflow', (status) => {
    expect(allowedActions(status, false, EVERY_KEY)).toEqual(['restore', 'dismiss', 'resolve']);
  });

  it.each(['visible', 'hidden', 'removed'] as const)(
    'offers only dismiss and resolve for a deleted %s workflow',
    (status) => {
      expect(allowedActions(status, true, EVERY_KEY)).toEqual(['dismiss', 'resolve']);
    },
  );

  it('drops the status-changing actions without the hide key', () => {
    const resolver = caller(['moderation.report.view', 'moderation.report.resolve']);
    expect(allowedActions('visible', false, resolver)).toEqual(['dismiss', 'resolve']);
    expect(allowedActions('hidden', false, resolver)).toEqual(['dismiss', 'resolve']);
  });

  it('offers nothing with the view and hide keys but not the resolve key', () => {
    const hider = caller(['moderation.report.view', 'moderation.content.hide']);
    expect(allowedActions('visible', false, hider)).toEqual([]);
    expect(allowedActions('hidden', false, hider)).toEqual([]);
  });

  it('offers nothing to a deactivated admin or before the caller is known', () => {
    expect(allowedActions('visible', false, { ...EVERY_KEY, adminDisabled: true })).toEqual([]);
    expect(allowedActions('visible', false, undefined)).toEqual([]);
  });
});
