import { setToken } from '@flow-cortex/api-client';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ADMIN_ME, renderRoute, staffMe, stubApi } from '../../test/fakeApi';
import { UserDetailPage } from './UserDetailPage';

const USER_ID = '481ea74a-eeb6-4ad6-99c1-3c117f57d230';
const USER_PATH = `GET /api/v1/users/${USER_ID}`;
const ME_PATH = 'GET /api/v1/auth/me';

beforeEach(() => setToken('admin-token-user-detail', true));
afterEach(() => {
  setToken(null);
  vi.unstubAllGlobals();
});

describe('UserDetailPage', () => {
  it('shows account details and suspends without deleting the account', async () => {
    let status = 'ACTIVE';
    const calls = stubApi({
      [ME_PATH]: { status: 200, body: ADMIN_ME },
      [USER_PATH]: () => ({
        status: 200,
        body: {
          id: USER_ID,
          email: 'member@example.com',
          name: 'Member',
          role: 'MEMBER',
          status,
          createdAt: '2026-10-01T10:00:00Z',
          updatedAt: '2026-10-02T10:00:00Z',
        },
      }),
      [`PUT /api/v1/users/${USER_ID}`]: () => {
        status = 'SUSPENDED';
        return { status: 200, body: { id: USER_ID, status } };
      },
    });
    renderRoute('/users/:userId', `/users/${USER_ID}`, <UserDetailPage />);

    expect(await screen.findByRole('heading', { name: 'Member' })).toBeInTheDocument();
    expect(screen.getByText(USER_ID)).toBeInTheDocument();
    await userEvent.click(await screen.findByRole('button', { name: 'Suspend account' }));

    expect(await screen.findByText('Account updated.')).toBeInTheDocument();
    expect(screen.getAllByText('Suspended').length).toBeGreaterThan(0);
    expect(calls.find((call) => call.method === 'PUT')?.body).toEqual({ status: 'SUSPENDED' });
    expect(calls.some((call) => call.method === 'DELETE')).toBe(false);
  });

  it('rejects malformed user IDs before requesting the API', () => {
    const calls = stubApi({});
    renderRoute('/users/:userId', '/users/not-a-uuid', <UserDetailPage />);

    expect(screen.getByRole('alert')).toHaveTextContent('The user ID is not valid.');
    expect(calls.filter((call) => call.path.startsWith('/api/v1/users'))).toHaveLength(0);
  });

  it('shows the state, sign-in methods and lockout, and hides the controls of a staff account without the manage key', async () => {
    stubApi({
      [ME_PATH]: {
        status: 200,
        body: staffMe('OPERATION_MANAGER', ['user.account.view', 'user.account.update']),
      },
      [USER_PATH]: {
        status: 200,
        body: {
          id: USER_ID,
          email: 'linh@example.com',
          name: 'Linh',
          username: 'linh',
          role: 'CUSTOMER_SUPPORT',
          status: 'ACTIVE',
          state: 'locked',
          staffTarget: true,
          adminDisabled: true,
          signInMethods: ['PASSWORD', 'GOOGLE'],
          lockedUntil: '2099-01-01T00:00:00Z',
          emailConfirmedAt: '2026-10-01T10:00:00Z',
        },
      },
    });
    renderRoute('/users/:userId', `/users/${USER_ID}`, <UserDetailPage />);

    expect(
      await screen.findByText('Only a super admin changes admin accounts.'),
    ).toBeInTheDocument();
    expect(screen.getAllByText('Locked').length).toBeGreaterThan(0);
    expect(screen.getByText('Password, Google')).toBeInTheDocument();
    expect(screen.getByText('Locked until')).toBeInTheDocument();
    expect(screen.getByText('Admin access deactivated')).toBeInTheDocument();
    expect(screen.getByText('linh')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Suspend account' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save role' })).not.toBeInTheDocument();
  });
});
