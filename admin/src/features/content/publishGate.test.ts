import { ADMIN_ME, staffMe } from '../../test/fakeApi';
import { publishGate } from './publishGate';

const EDITOR = staffMe('CONTENT_EDITOR', ['content.document.view', 'content.document.manage']);
const VIEWER = staffMe('CONTENT_EDITOR', ['content.document.view']);

describe('publishGate', () => {
  it('is enabled for a caller holding the manage key on a saved draft', () => {
    expect(publishGate({ me: EDITOR, unsaved: false, version: 1 })).toEqual({
      enabled: true,
      reason: null,
    });
  });

  it('is enabled for a super admin, whose keys list every catalog key', () => {
    expect(publishGate({ me: ADMIN_ME, unsaved: false, version: 4 }).enabled).toBe(true);
  });

  it('names the missing permission for a caller without the manage key', () => {
    expect(publishGate({ me: VIEWER, unsaved: false, version: 1 })).toEqual({
      enabled: false,
      reason: 'forbidden',
    });
  });

  it('names the missing permission for a deactivated admin that still lists the key', () => {
    expect(
      publishGate({ me: { ...EDITOR, adminDisabled: true }, unsaved: false, version: 1 }),
    ).toEqual({ enabled: false, reason: 'forbidden' });
  });

  it('is disabled before the caller is known', () => {
    expect(publishGate({ me: undefined, unsaved: false, version: 1 }).reason).toBe('forbidden');
  });

  it('asks to save first while the editor holds unsaved changes', () => {
    expect(publishGate({ me: EDITOR, unsaved: true, version: 3 })).toEqual({
      enabled: false,
      reason: 'unsaved',
    });
  });

  it('asks to save first on a document with no draft yet and unsaved changes', () => {
    expect(publishGate({ me: EDITOR, unsaved: true, version: 0 }).reason).toBe('unsaved');
  });

  it('names the missing draft when nothing was ever saved', () => {
    expect(publishGate({ me: EDITOR, unsaved: false, version: 0 })).toEqual({
      enabled: false,
      reason: 'no-draft',
    });
  });

  it('reports the permission before the unsaved changes', () => {
    expect(publishGate({ me: VIEWER, unsaved: true, version: 0 }).reason).toBe('forbidden');
  });
});
