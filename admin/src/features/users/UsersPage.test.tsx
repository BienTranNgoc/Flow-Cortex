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
  roleName: 'Member',
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
      roleName: 'Operation Manager',
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
    expect(name).toHaveValue('');
    expect(name).toHaveAttribute('placeholder', 'Ops');
    await userEvent.type(name, 'Ops Lead');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(calls.some((call) => call.method === 'PUT')).toBe(true));
    expect(calls.find((call) => call.method === 'PUT')?.body).toEqual({ name: 'Ops Lead' });
  });

  it('keeps the Creator role selected and sends no role when it is untouched', async () => {
    const creator = {
      ...ALICE,
      id: 'u3',
      email: 'creator@flowcortex.dev',
      role: 'CREATOR',
      roleName: 'Creator',
    };
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
    expect(calls.find((call) => call.method === 'PUT')?.body).toEqual({});
  });

  it('editOnlyTheRole', async () => {
    const calls = stubApi({
      [ME_PATH]: { status: 200, body: ADMIN_ME },
      [STATS_PATH]: STATS,
      [LIST_PATH]: pageOf([ALICE]),
      'PUT /api/v1/users/u1': { status: 200, body: ALICE },
    });
    renderRoute('/users', '/users', <UsersPage />);
    await screen.findByText(ALICE.email);
    await userEvent.click(await screen.findByRole('button', { name: 'Edit' }));
    const email = screen.getByLabelText('Email', { selector: '#edit-email-u1' });
    expect(email).toHaveValue('');
    expect(email).toHaveAttribute('placeholder', ALICE.email);
    await userEvent.selectOptions(
      screen.getByLabelText('Role', { selector: '#edit-role-u1' }),
      'CREATOR',
    );
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(calls.some((call) => call.method === 'PUT')).toBe(true));
    expect(calls.find((call) => call.method === 'PUT')?.body).toEqual({ role: 'CREATOR' });
  });

  it('refuses an invalid email only when one is typed', async () => {
    const calls = stubApi({
      [ME_PATH]: { status: 200, body: ADMIN_ME },
      [STATS_PATH]: STATS,
      [LIST_PATH]: pageOf([ALICE]),
      'PUT /api/v1/users/u1': { status: 200, body: ALICE },
    });
    renderRoute('/users', '/users', <UsersPage />);
    await screen.findByText(ALICE.email);
    await userEvent.click(await screen.findByRole('button', { name: 'Edit' }));
    await userEvent.type(screen.getByLabelText('Email', { selector: '#edit-email-u1' }), 'nope');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByText('Enter a valid email')).toBeInTheDocument();
    expect(calls.some((call) => call.method === 'PUT')).toBe(false);
  });

  it('shows the message of a masked value the server refuses', async () => {
    stubApi({
      [ME_PATH]: { status: 200, body: ADMIN_ME },
      [STATS_PATH]: STATS,
      [LIST_PATH]: pageOf([ALICE]),
      'PUT /api/v1/users/u1': {
        status: 422,
        body: {
          status: 422,
          code: 'user.maskedValue',
          detail: 'That value is the masked form of the current one.',
        },
      },
    });
    renderRoute('/users', '/users', <UsersPage />);
    await screen.findByText(ALICE.email);
    await userEvent.click(await screen.findByRole('button', { name: 'Edit' }));
    await userEvent.type(screen.getByLabelText('Name', { selector: '#edit-name-u1' }), 'A*****');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(
      await screen.findByText('That value is the masked form of the current one.'),
    ).toBeInTheDocument();
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

  describe('role filter', () => {
    const SUPPORT_TIER_2 = { code: 'SUPPORT_TIER_2', name: 'Support Tier 2', system: false };

    it('offers a custom role nobody on the page holds and filters the list by it', async () => {
      const calls = stubApi({
        [ME_PATH]: { status: 200, body: ADMIN_ME },
        [STATS_PATH]: STATS,
        [LIST_PATH]: pageOf([ALICE], { total: 60 }),
        'GET /api/v1/admin/roles': { status: 200, body: [SUPPORT_TIER_2] },
      });
      renderRoute('/users', '/users', <UsersPage />);
      await screen.findByText(ALICE.email);

      const role = screen.getByLabelText('Role', { selector: '#users-role' });
      await screen.findByRole('option', { name: 'Support Tier 2' });
      await userEvent.selectOptions(role, 'SUPPORT_TIER_2');

      await waitFor(() =>
        expect(
          calls.filter((call) => call.path === '/api/v1/users').at(-1)?.query.get('role'),
        ).toBe('SUPPORT_TIER_2'),
      );
    });

    it('keeps the built-in roles and the role in the address without the role list', async () => {
      stubApi({
        [ME_PATH]: {
          status: 200,
          body: staffMe('OPERATION_MANAGER', ['user.account.view']),
        },
        [STATS_PATH]: STATS,
        [LIST_PATH]: pageOf([ALICE]),
      });
      renderRoute('/users', '/users?role=SUPPORT_TIER_2', <UsersPage />);
      await screen.findByText(ALICE.email);

      const role = screen.getByLabelText('Role', { selector: '#users-role' });
      expect(within(role).getByRole('option', { name: 'Creator' })).toBeInTheDocument();
      expect(role).toHaveValue('SUPPORT_TIER_2');
    });
  });

  describe('statistics', () => {
    it('says the statistics failed, keeps the table and recovers on Retry', async () => {
      let statsReply: { status: number; body: unknown } = {
        status: 500,
        body: { status: 500, detail: 'boom' },
      };
      stubApi({
        [ME_PATH]: { status: 200, body: ADMIN_ME },
        [STATS_PATH]: () => statsReply,
        [LIST_PATH]: pageOf([ALICE]),
      });
      renderRoute('/users', '/users', <UsersPage />);
      await screen.findByText(ALICE.email);

      const message = await screen.findByText('Could not load user statistics.');
      expect(screen.queryByRole('region', { name: 'User statistics' })).not.toBeInTheDocument();
      expect(screen.getByText(ALICE.email)).toBeInTheDocument();

      statsReply = STATS;
      await userEvent.click(
        within(message.closest('[role="alert"]') as HTMLElement).getByRole('button', {
          name: 'Retry',
        }),
      );

      expect(await screen.findByRole('region', { name: 'User statistics' })).toBeInTheDocument();
      expect(screen.queryByText('Could not load user statistics.')).not.toBeInTheDocument();
    });

    it('keeps the cards already shown when a refresh of the statistics fails', async () => {
      let statsReply: { status: number; body: unknown } = STATS;
      stubApi({
        [ME_PATH]: { status: 200, body: ADMIN_ME },
        [STATS_PATH]: () => statsReply,
        [LIST_PATH]: pageOf([ALICE]),
      });
      renderRoute('/users', '/users', <UsersPage />);
      await screen.findByRole('region', { name: 'User statistics' });

      statsReply = { status: 500, body: { status: 500, detail: 'boom' } };
      await userEvent.click(screen.getByRole('button', { name: 'Refresh' }));

      await screen.findByText('Could not load user statistics.');
      expect(screen.getByRole('region', { name: 'User statistics' })).toBeInTheDocument();
    });
  });

  describe('account control', () => {
    it('hides edit for a staff row without the admin-account key and says why', async () => {
      const support = {
        ...ALICE,
        id: 'u9',
        email: 'linh@flowcortex.dev',
        role: 'CUSTOMER_SUPPORT',
        roleName: 'Customer Support',
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
