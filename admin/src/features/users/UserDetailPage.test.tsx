import { setToken } from '@flow-cortex/api-client';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import i18n from '../../lib/i18n';
import viUsers from '../../locales/vi/users.json';
import { renderRoute, stubApi } from '../../test/fakeApi';
import { UserDetailPage } from './UserDetailPage';

const USER_ID = '481ea74a-eeb6-4ad6-99c1-3c117f57d230';
const USER_PATH = `GET /api/v1/users/${USER_ID}`;

beforeEach(() => setToken('admin-token-user-detail', true));
afterEach(() => {
  setToken(null);
  vi.unstubAllGlobals();
});

describe('UserDetailPage', () => {
  it('shows account details and suspends without deleting the account', async () => {
    let status = 'ACTIVE';
    const calls = stubApi({
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
    await userEvent.click(screen.getByRole('button', { name: 'Suspend account' }));

    expect(await screen.findByText('Account updated.')).toBeInTheDocument();
    expect(screen.getAllByText('Suspended').length).toBeGreaterThan(0);
    expect(calls.find((call) => call.method === 'PUT')?.body).toEqual({ status: 'SUSPENDED' });
    expect(calls.some((call) => call.method === 'DELETE')).toBe(false);
  });

  it('rejects malformed user IDs before requesting the API', () => {
    const calls = stubApi({});
    renderRoute('/users/:userId', '/users/not-a-uuid', <UserDetailPage />);

    expect(screen.getByRole('alert')).toHaveTextContent('The user ID is not valid.');
    expect(calls).toHaveLength(0);
  });

  describe('in Vietnamese', () => {
    beforeEach(async () => {
      await i18n.changeLanguage('vi');
    });
    afterEach(async () => {
      await i18n.changeLanguage('en');
    });

    it('labels the page heading, status pills and account panels in Vietnamese', async () => {
      stubApi({
        [USER_PATH]: {
          status: 200,
          body: {
            id: USER_ID,
            email: 'member@example.com',
            name: 'Member',
            role: 'MEMBER',
            status: 'SUSPENDED',
            createdAt: '2026-10-01T10:00:00Z',
            updatedAt: '2026-10-02T10:00:00Z',
          },
        },
      });
      renderRoute('/users/:userId', `/users/${USER_ID}`, <UserDetailPage />);

      expect(await screen.findByRole('heading', { name: 'Member' })).toBeInTheDocument();
      expect(screen.getByRole('link', { name: viUsers.detail.allUsers })).toHaveAttribute(
        'href',
        '/users',
      );
      expect(screen.getByText(viUsers.detail.eyebrow)).toBeInTheDocument();
      expect(screen.getAllByText(viUsers.status.SUSPENDED)).toHaveLength(2);
      expect(screen.getByText(viUsers.detail.accountInfoHint)).toBeInTheDocument();
      expect(
        screen.getByText(viUsers.detail.role, { selector: 'dt' }).nextElementSibling,
      ).toHaveTextContent(viUsers.role.MEMBER);
      expect(screen.getByRole('button', { name: viUsers.detail.reactivate })).toBeInTheDocument();
    });
  });
});
