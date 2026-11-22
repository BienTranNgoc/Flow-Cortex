import { setToken, type RbacRuntime, type RoleResponse } from '@flow-cortex/api-client';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ADMIN_ME, renderRoute, staffMe, stubApi } from '../../test/fakeApi';
import { RoleDetailPage } from './RoleDetailPage';
import { RolesPage } from './RolesPage';

const ME = 'GET /api/v1/auth/me';
const ROLES = 'GET /api/v1/admin/roles';
const RUNTIME = '/api/v1/admin/rbac/runtime';

const CUSTOM_ROLE: RoleResponse = {
  code: 'BIEN_TAP_VIEN',
  name: 'Biên tập viên',
  description: null,
  system: false,
  staff: true,
  superAdmin: false,
  permissions: ['content.help_article.view'],
  permissionsVersion: 3,
  accountCount: 0,
};

const CATALOG = [
  {
    key: 'content.help_article.view',
    module: 'content',
    feature: 'help_article',
    action: 'view',
    superAdminOnly: false,
  },
  {
    key: 'content.help_article.manage',
    module: 'content',
    feature: 'help_article',
    action: 'manage',
    superAdminOnly: false,
  },
];

const ON: RbacRuntime = {
  mutationsEnabled: true,
  source: 'DEFAULT',
  configuredValue: null,
  updatedBy: null,
  updatedAt: null,
};
const OFF: RbacRuntime = {
  mutationsEnabled: false,
  source: 'STORED',
  configuredValue: null,
  updatedBy: 'admin@flowcortex.dev',
  updatedAt: '2026-11-20T03:00:00Z',
};
const VETOED: RbacRuntime = { ...OFF, source: 'VETOED', configuredValue: false };

const showModal = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'showModal');
const close = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'close');

beforeEach(() => {
  setToken('admin-token-rbac-runtime', true);
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
    configurable: true,
    value(this: HTMLDialogElement) {
      this.setAttribute('open', '');
    },
  });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', {
    configurable: true,
    value(this: HTMLDialogElement) {
      this.removeAttribute('open');
      this.dispatchEvent(new Event('close'));
    },
  });
  // An open modal dialog makes the rest of the page inert, so focus() outside it does nothing.
  const focus = HTMLElement.prototype.focus;
  vi.spyOn(HTMLElement.prototype, 'focus').mockImplementation(function (
    this: HTMLElement,
    options?: FocusOptions,
  ) {
    const modal = document.querySelector('dialog[open]');
    if (modal && !modal.contains(this)) return;
    focus.call(this, options);
  });
});
afterEach(() => {
  setToken(null);
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  for (const [name, descriptor] of [['showModal', showModal], ['close', close]] as const) {
    if (descriptor) Object.defineProperty(HTMLDialogElement.prototype, name, descriptor);
    else Reflect.deleteProperty(HTMLDialogElement.prototype, name);
  }
});

function renderList() {
  return renderRoute('/roles', '/roles', <RolesPage />);
}

function renderDetail() {
  return renderRoute('/roles/:code', `/roles/${CUSTOM_ROLE.code}`, <RoleDetailPage />);
}

describe('Roles page RBAC switch', () => {
  it('turns role edits off with a reason and disables the create control', async () => {
    let current = ON;
    const calls = stubApi({
      [ME]: { status: 200, body: ADMIN_ME },
      [ROLES]: { status: 200, body: [CUSTOM_ROLE] },
      [`GET ${RUNTIME}`]: () => ({ status: 200, body: current }),
      [`PUT ${RUNTIME}`]: () => {
        current = OFF;
        return { status: 200, body: current };
      },
    });
    renderList();

    expect(await screen.findByRole('link', { name: 'Create role' })).toBeInTheDocument();
    const toggle = await screen.findByRole('switch', { name: 'Role and permission changes' });
    expect(toggle).toBeChecked();

    await userEvent.click(toggle);
    const dialog = await screen.findByRole('dialog');
    await userEvent.type(within(dialog).getByLabelText('Reason (optional)'), '  Audit  ');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Turn off' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(calls.find((call) => call.method === 'PUT')?.body).toEqual({
      mutationsEnabled: false,
      reason: 'Audit',
    });
    expect(screen.getByRole('switch', { name: 'Role and permission changes' })).not.toBeChecked();
    expect(screen.getByRole('switch', { name: 'Role and permission changes' })).toHaveFocus();
    expect(screen.getByText('Set by an admin')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent(
      'Role and permission changes are turned off',
    );
    expect(screen.getByRole('button', { name: 'Create role' })).toBeDisabled();
    expect(screen.queryByRole('link', { name: 'Create role' })).not.toBeInTheDocument();
  });

  it('shows an environment veto with a disabled toggle and no edits', async () => {
    stubApi({
      [ME]: { status: 200, body: ADMIN_ME },
      [ROLES]: { status: 200, body: [CUSTOM_ROLE] },
      [`GET ${RUNTIME}`]: { status: 200, body: VETOED },
    });
    renderList();

    const toggle = await screen.findByRole('switch', { name: 'Role and permission changes' });
    expect(toggle).toBeDisabled();
    expect(toggle).not.toBeChecked();
    expect(toggle).toHaveAccessibleDescription('Turned off by the server configuration');
    expect(screen.getByRole('button', { name: 'Create role' })).toBeDisabled();
  });

  it('disables edits and offers a retry while the switch cannot be read', async () => {
    let healthy = false;
    stubApi({
      [ME]: { status: 200, body: ADMIN_ME },
      [ROLES]: { status: 200, body: [CUSTOM_ROLE] },
      [`GET ${RUNTIME}`]: () =>
        healthy ? { status: 200, body: ON } : { status: 500, body: { status: 500 } },
    });
    renderList();

    expect(await screen.findByText(/^Could not load the role edit switch/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Create role' })).toBeDisabled();

    healthy = true;
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('link', { name: 'Create role' })).toBeInTheDocument();
  });

  it('shows a view-only caller no switch and sends no switch request', async () => {
    const calls = stubApi({
      [ME]: { status: 200, body: staffMe('PLATFORM_OPERATOR', ['system.role.view']) },
      [ROLES]: { status: 200, body: [CUSTOM_ROLE] },
    });
    renderList();

    expect(await screen.findByRole('link', { name: 'Open role Biên tập viên' })).toBeInTheDocument();
    expect(screen.queryByRole('switch')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Create role' })).not.toBeInTheDocument();
    expect(calls.some((call) => call.path === RUNTIME)).toBe(false);
  });
});

describe('Role detail page while role edits are off', () => {
  it('disables rename, matrix, save and delete and says why', async () => {
    stubApi({
      [ME]: { status: 200, body: ADMIN_ME },
      [`GET /api/v1/admin/roles/${CUSTOM_ROLE.code}`]: { status: 200, body: CUSTOM_ROLE },
      ['GET /api/v1/admin/permission-catalog']: { status: 200, body: CATALOG },
      [`GET ${RUNTIME}`]: { status: 200, body: OFF },
    });
    renderDetail();

    expect(
      await screen.findByText('Role and permission changes are turned off'),
    ).toBeInTheDocument();
    expect(screen.getByLabelText('Name')).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Delete role' })).toBeDisabled();
    for (const box of screen.getAllByRole('checkbox')) expect(box).toBeDisabled();
  });

  it('keeps rename and delete enabled while the switch reads on', async () => {
    stubApi({
      [ME]: { status: 200, body: ADMIN_ME },
      [`GET /api/v1/admin/roles/${CUSTOM_ROLE.code}`]: { status: 200, body: CUSTOM_ROLE },
      ['GET /api/v1/admin/permission-catalog']: { status: 200, body: CATALOG },
      [`GET ${RUNTIME}`]: { status: 200, body: ON },
    });
    renderDetail();

    await waitFor(() => expect(screen.getByLabelText('Name')).toBeEnabled());
    expect(screen.getByRole('button', { name: 'Delete role' })).toBeEnabled();
    expect(screen.queryByText('Role and permission changes are turned off')).not.toBeInTheDocument();
  });
});
