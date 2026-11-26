import { setToken } from '@flow-cortex/api-client';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import i18n from '../../lib/i18n';
import viUsers from '../../locales/vi/users.json';
import { ADMIN_ME, renderRoute, staffMe, stubApi } from '../../test/fakeApi';
import { UserDetailPage } from './UserDetailPage';

const USER_ID = '481ea74a-eeb6-4ad6-99c1-3c117f57d230';
const USER_PATH = `GET /api/v1/users/${USER_ID}`;
const ME_PATH = 'GET /api/v1/auth/me';

const showModal = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'showModal');
const close = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'close');

beforeEach(() => {
  setToken('admin-token-user-detail', true);
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
});
afterEach(() => {
  setToken(null);
  vi.unstubAllGlobals();
  for (const [name, descriptor] of [
    ['showModal', showModal],
    ['close', close],
  ] as const) {
    if (descriptor) Object.defineProperty(HTMLDialogElement.prototype, name, descriptor);
    else Reflect.deleteProperty(HTMLDialogElement.prototype, name);
  }
});

const TOTP_USER = {
  id: USER_ID,
  email: 'linh@example.com',
  name: 'Linh',
  role: 'MEMBER',
  status: 'ACTIVE',
  totpEnabled: true,
};
const RESET_PATH = `POST /api/v1/users/${USER_ID}/totp/reset`;

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
          totpEnabled: true,
        },
      },
    });
    renderRoute('/users/:userId', `/users/${USER_ID}`, <UserDetailPage />);

    expect(
      await screen.findByText('Only a super admin changes admin accounts.'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reset two-factor authentication' })).toBeNull();
    expect(screen.getAllByText('Locked').length).toBeGreaterThan(0);
    expect(screen.getByText('Password, Google')).toBeInTheDocument();
    expect(screen.getByText('Locked until')).toBeInTheDocument();
    expect(screen.getByText('Admin access deactivated')).toBeInTheDocument();
    expect(screen.getByText('linh')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Suspend account' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save role' })).not.toBeInTheDocument();
  });

  describe('two-factor authentication', () => {
    const RESET = 'Reset two-factor authentication';

    it('shows whether it is on', async () => {
      stubApi({
        [ME_PATH]: { status: 200, body: ADMIN_ME },
        [USER_PATH]: { status: 200, body: { ...TOTP_USER, totpEnabled: false } },
      });
      renderRoute('/users/:userId', `/users/${USER_ID}`, <UserDetailPage />);

      expect(await screen.findByText('Two-factor authentication')).toBeInTheDocument();
      expect(screen.getByText('Off')).toBeInTheDocument();
    });

    it('resets another enrolled account after a confirmation and shows it off', async () => {
      let enabled = true;
      const calls = stubApi({
        [ME_PATH]: { status: 200, body: ADMIN_ME },
        [USER_PATH]: () => ({ status: 200, body: { ...TOTP_USER, totpEnabled: enabled } }),
        [RESET_PATH]: () => {
          enabled = false;
          return { status: 204 };
        },
      });
      const user = userEvent.setup();
      renderRoute('/users/:userId', `/users/${USER_ID}`, <UserDetailPage />);

      expect(screen.queryByRole('button', { name: RESET })).not.toBeInTheDocument();
      await user.click(await screen.findByRole('button', { name: RESET }));
      expect(calls.some((call) => call.path.endsWith('/totp/reset'))).toBe(false);
      const dialog = screen.getByRole('dialog');
      expect(dialog).toHaveTextContent('Linh will be signed out everywhere');
      await user.click(within(dialog).getByRole('button', { name: 'Reset' }));

      expect(await screen.findByText('Two-factor authentication was reset.')).toBeInTheDocument();
      expect(await screen.findByText('Off')).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: RESET })).not.toBeInTheDocument();
      expect(calls.filter((call) => call.path.endsWith('/totp/reset'))).toHaveLength(1);
    });

    it('cancelling the confirmation resets nothing', async () => {
      const calls = stubApi({
        [ME_PATH]: { status: 200, body: ADMIN_ME },
        [USER_PATH]: { status: 200, body: TOTP_USER },
      });
      const user = userEvent.setup();
      renderRoute('/users/:userId', `/users/${USER_ID}`, <UserDetailPage />);

      await user.click(await screen.findByRole('button', { name: RESET }));
      await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel' }));

      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(calls.some((call) => call.path.endsWith('/totp/reset'))).toBe(false);
    });

    it('keeps the confirmation open with the reason when the server refuses', async () => {
      stubApi({
        [ME_PATH]: { status: 200, body: ADMIN_ME },
        [USER_PATH]: { status: 200, body: TOTP_USER },
        [RESET_PATH]: { status: 403, body: { status: 403, detail: 'You do not have permission.' } },
      });
      const user = userEvent.setup();
      renderRoute('/users/:userId', `/users/${USER_ID}`, <UserDetailPage />);

      await user.click(await screen.findByRole('button', { name: RESET }));
      await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Reset' }));

      expect(await within(screen.getByRole('dialog')).findByRole('alert')).toHaveTextContent(
        'You do not have permission.',
      );
    });

    it('offers no reset on the caller’s own record', async () => {
      stubApi({
        [ME_PATH]: { status: 200, body: { ...ADMIN_ME, id: USER_ID } },
        [USER_PATH]: { status: 200, body: TOTP_USER },
      });
      renderRoute('/users/:userId', `/users/${USER_ID}`, <UserDetailPage />);

      expect(await screen.findByText('On')).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: RESET })).not.toBeInTheDocument();
    });

    it('offers no reset while it is off', async () => {
      stubApi({
        [ME_PATH]: { status: 200, body: ADMIN_ME },
        [USER_PATH]: { status: 200, body: { ...TOTP_USER, totpEnabled: false } },
      });
      renderRoute('/users/:userId', `/users/${USER_ID}`, <UserDetailPage />);

      await screen.findByText('Off');
      expect(screen.queryByRole('button', { name: RESET })).not.toBeInTheDocument();
    });

    it('offers no reset to a caller without the account-update key', async () => {
      stubApi({
        [ME_PATH]: { status: 200, body: staffMe('CUSTOMER_SUPPORT', ['user.account.view']) },
        [USER_PATH]: { status: 200, body: TOTP_USER },
      });
      renderRoute('/users/:userId', `/users/${USER_ID}`, <UserDetailPage />);

      expect(await screen.findByText('On')).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: RESET })).not.toBeInTheDocument();
    });

    it('offers a reset of an admin account to a caller holding the admin-account key', async () => {
      stubApi({
        [ME_PATH]: {
          status: 200,
          body: staffMe('OPERATION_MANAGER', [
            'user.account.update',
            'system.admin_account.manage',
          ]),
        },
        [USER_PATH]: { status: 200, body: { ...TOTP_USER, role: 'ADMIN', staffTarget: true } },
      });
      renderRoute('/users/:userId', `/users/${USER_ID}`, <UserDetailPage />);

      expect(await screen.findByRole('button', { name: RESET })).toBeInTheDocument();
    });

    it('shows the reset in Vietnamese', async () => {
      await i18n.changeLanguage('vi');
      try {
        stubApi({
          [ME_PATH]: { status: 200, body: ADMIN_ME },
          [USER_PATH]: { status: 200, body: TOTP_USER },
        });
        renderRoute('/users/:userId', `/users/${USER_ID}`, <UserDetailPage />);

        expect(
          await screen.findByRole('button', { name: viUsers.detail.totpReset }),
        ).toBeInTheDocument();
        expect(screen.getByText(viUsers.detail.totpOn)).toBeInTheDocument();
      } finally {
        await i18n.changeLanguage('en');
      }
    });
  });
});
