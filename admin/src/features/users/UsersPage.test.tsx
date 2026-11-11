import { setToken } from '@flow-cortex/api-client';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ADMIN_ME, renderRoute, staffMe, stubApi } from '../../test/fakeApi';
import { UsersPage } from './UsersPage';

const ME_PATH = 'GET /api/v1/auth/me';
const LIST_PATH = 'GET /api/v1/users';
const STATS_PATH = 'GET /api/v1/users/stats';

const ALICE = {
  id: 'u1',
  email: 'alice@flowcortex.dev',
  name: 'Alice',
  role: 'MEMBER',
  status: 'ACTIVE',
  state: 'active',
  staffTarget: false,
};

const pageOf = (items: unknown[], extra: { page?: number; total?: number } = {}) => ({
  status: 200,
  body: { items, page: extra.page ?? 0, size: 20, total: extra.total ?? items.length },
});

const STATS = {
  status: 200,
  body: { total: 7, active: 4, pendingConfirmation: 1, locked: 1, suspended: 1, newLast7Days: 3 },
};

beforeEach(() => setToken('admin-token-users', true));
afterEach(() => {
  setToken(null);
  vi.unstubAllGlobals();
});

describe('UsersPage', () => {
  it('editingAStaffRowsNameLeavesItsRoleUnchanged', async () => {
    const manager = {
      ...ALICE,
      id: 'u4',
      email: 'ops@flowcortex.dev',
      name: 'Ops',
      role: 'OPERATION_MANAGER',
      staffTarget: true,
    };
    const calls = stubApi({
      [ME_PATH]: { status: 200, body: ADMIN_ME },
      [STATS_PATH]: STATS,
      [LIST_PATH]: pageOf([manager]),
      'PUT /api/v1/users/u4': { status: 200, body: manager },
    });
    renderRoute('/users', '/users', <UsersPage />);
    await screen.findByText(manager.email);
    await userEvent.click(await screen.findByRole('button', { name: 'Edit' }));
    expect(screen.getByLabelText('Role', { selector: '#edit-role-u4' })).toHaveValue(
      'OPERATION_MANAGER',
    );
    const name = screen.getByLabelText('Name', { selector: '#edit-name-u4' });
    await userEvent.clear(name);
    await userEvent.type(name, 'Ops Lead');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(calls.some((call) => call.method === 'PUT')).toBe(true));
    const body = calls.find((call) => call.method === 'PUT')?.body;
    expect(body).toMatchObject({ name: 'Ops Lead' });
    expect(body).not.toHaveProperty('role');
  });

  it('keeps the Creator role selected and sends no role when it is untouched', async () => {
    const creator = { ...ALICE, id: 'u3', email: 'creator@flowcortex.dev', role: 'CREATOR' };
    const calls = stubApi({
      [ME_PATH]: { status: 200, body: ADMIN_ME },
      [STATS_PATH]: STATS,
      [LIST_PATH]: pageOf([creator]),
      'PUT /api/v1/users/u3': { status: 200, body: creator },
    });
    renderRoute('/users', '/users', <UsersPage />);
    await screen.findByText(creator.email);
    await userEvent.click(await screen.findByRole('button', { name: 'Edit' }));
    expect(screen.getByLabelText('Role', { selector: '#edit-role-u3' })).toHaveValue('CREATOR');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(calls.some((call) => call.method === 'PUT')).toBe(true));
    expect(calls.find((call) => call.method === 'PUT')?.body).not.toHaveProperty('role');
  });

  it('sends one list request for a settled search and resets the page', async () => {
    const calls = stubApi({
      [ME_PATH]: { status: 200, body: ADMIN_ME },
      [STATS_PATH]: STATS,
      [LIST_PATH]: pageOf([ALICE], { page: 2, total: 60 }),
    });
    renderRoute('/users', '/users?page=2', <UsersPage />);
    await screen.findByText(ALICE.email);
    const before = calls.filter((call) => call.path === '/api/v1/users').length;

    await userEvent.type(screen.getByLabelText('Search'), 'nguyen');

    await waitFor(() =>
      expect(calls.filter((call) => call.path === '/api/v1/users')).toHaveLength(before + 1),
    );
    const last = calls.filter((call) => call.path === '/api/v1/users').at(-1);
    expect(last?.query.get('q')).toBe('nguyen');
    expect(last?.query.get('page')).toBe('0');
  });

  describe('account control', () => {
    it('hides edit for a staff row without the admin-account key and says why', async () => {
      const support = {
        ...ALICE,
        id: 'u9',
        email: 'linh@flowcortex.dev',
        role: 'CUSTOMER_SUPPORT',
        staffTarget: true,
      };
      stubApi({
        [ME_PATH]: {
          status: 200,
          body: staffMe('OPERATION_MANAGER', ['user.account.view', 'user.account.update']),
        },
        [STATS_PATH]: STATS,
        [LIST_PATH]: pageOf([support, { ...ALICE, id: 'u10', email: 'ba@flowcortex.dev' }]),
      });
      renderRoute('/users', '/users', <UsersPage />);

      const staffRow = (await screen.findByText(support.email)).closest('tr') as HTMLElement;
      const memberRow = (await screen.findByText('ba@flowcortex.dev')).closest('tr') as HTMLElement;
      await waitFor(() =>
        expect(within(memberRow).getByRole('button', { name: 'Edit' })).toBeInTheDocument(),
      );
      expect(within(staffRow).queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument();
      expect(
        within(staffRow).getByText('Only a super admin changes admin accounts.'),
      ).toBeInTheDocument();
    });
  });
});
