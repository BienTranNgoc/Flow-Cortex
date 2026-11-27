import { setToken } from '@flow-cortex/api-client';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, staffMe, stubApi } from '../../test/fakeApi';
import type { Handler } from '../../test/fakeApi';
import { UserDetailPage } from './UserDetailPage';

const USER_ID = '481ea74a-eeb6-4ad6-99c1-3c117f57d230';
const USER_PATH = `GET /api/v1/users/${USER_ID}`;
const IDENTITY_PATH = `GET /api/v1/users/${USER_ID}/identity`;
const ME_PATH = 'GET /api/v1/auth/me';
const SHOW = { name: 'Show contact details' };

const MASKED_USER = {
  id: USER_ID,
  email: 'l****e@example.com',
  name: 'L*** T*** L***',
  role: 'MEMBER',
  status: 'ACTIVE',
};

beforeEach(() => setToken('admin-token-user-reveal', true));
afterEach(() => {
  setToken(null);
  vi.unstubAllGlobals();
});

function stubDetail(keys: Parameters<typeof staffMe>[1], identity?: Handler) {
  return stubApi({
    [ME_PATH]: { status: 200, body: staffMe('OPERATION_MANAGER', keys) },
    [USER_PATH]: { status: 200, body: MASKED_USER },
    [`GET /api/v1/users/${USER_ID}/activity`]: { status: 200, body: { items: [] } },
    ...(identity ? { [IDENTITY_PATH]: identity } : {}),
  });
}

describe('UserDetailPage reveal', () => {
  it('shows the revealed values for this page view only', async () => {
    stubDetail(['user.account.view', 'user.account.view_pii'], {
      status: 200,
      body: { id: USER_ID, email: 'lan.le@example.com', name: 'Lê Thị Lan' },
    });
    const first = renderRoute('/users/:userId', `/users/${USER_ID}`, <UserDetailPage />);

    expect(await screen.findByRole('heading', { name: 'L*** T*** L***' })).toBeInTheDocument();
    await userEvent.click(await screen.findByRole('button', SHOW));

    expect(await screen.findByRole('heading', { name: 'Lê Thị Lan' })).toBeInTheDocument();
    expect(screen.getAllByText('lan.le@example.com').length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', SHOW)).not.toBeInTheDocument();
    const cached = first.queryClient
      .getQueryCache()
      .findAll()
      .map((query) => JSON.stringify(query.state.data ?? null))
      .join('');
    expect(cached).not.toContain('lan.le@example.com');

    first.unmount();
    await waitFor(() => expect(first.queryClient.getMutationCache().getAll()).toHaveLength(0));
    renderRoute('/users/:userId', `/users/${USER_ID}`, <UserDetailPage />);

    expect(await screen.findByRole('heading', { name: 'L*** T*** L***' })).toBeInTheDocument();
    expect(screen.queryByText('lan.le@example.com')).not.toBeInTheDocument();
  });

  it('keeps the masked values and says why when the audit trail cannot record the reveal', async () => {
    stubDetail(['user.account.view', 'user.account.view_pii'], {
      status: 503,
      body: { status: 503, detail: 'audit trail could not record' },
    });
    renderRoute('/users/:userId', `/users/${USER_ID}`, <UserDetailPage />);

    await userEvent.click(await screen.findByRole('button', SHOW));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'because the audit trail could not record the request',
    );
    expect(screen.getByRole('heading', { name: 'L*** T*** L***' })).toBeInTheDocument();
    expect(screen.getByRole('button', SHOW)).toBeEnabled();
  });

  it('shows no control to a caller without the key', async () => {
    const calls = stubDetail(['user.account.view']);
    renderRoute('/users/:userId', `/users/${USER_ID}`, <UserDetailPage />);

    expect(await screen.findByRole('heading', { name: 'L*** T*** L***' })).toBeInTheDocument();
    expect(screen.queryByRole('button', SHOW)).not.toBeInTheDocument();
    expect(calls.some((call) => call.path === IDENTITY_PATH.replace('GET ', ''))).toBe(false);
  });
});
