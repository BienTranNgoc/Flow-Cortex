import { setToken } from '@flow-cortex/api-client';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import i18n from '../../lib/i18n';
import viAuth from '../../locales/vi/auth.json';
import { renderRoute, stubApi } from '../../test/fakeApi';
import { ProfilePage } from './ProfilePage';

const ME = {
  id: 'u1',
  email: 'member1@flowcortex.dev',
  name: 'Member One',
  role: 'MEMBER',
  status: 'ACTIVE',
};

function renderProfile() {
  return renderRoute('/profile', '/profile', <ProfilePage />);
}

describe('ProfilePage', () => {
  beforeEach(() => setToken('t', true));
  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
  });

  it('shows the account status from /me and hides placeholder stats by default', async () => {
    stubApi({ 'GET /api/v1/auth/me': { status: 200, body: { ...ME, status: 'SUSPENDED' } } });

    renderProfile();

    expect(await screen.findByText('SUSPENDED')).toBeInTheDocument();
    expect(screen.queryByRole('list', { name: 'Account statistics' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Request deletion' })).not.toBeInTheDocument();
  });

  it('offers no API key, since the backend issues none', async () => {
    stubApi({ 'GET /api/v1/auth/me': { status: 200, body: ME } });

    renderProfile();

    expect(await screen.findByRole('heading', { name: 'Identity' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'API key' })).not.toBeInTheDocument();
    expect(screen.queryByText(/Authorization: Bearer/)).not.toBeInTheDocument();
  });

  it('shows a dash when /me has no status', async () => {
    const { status: _status, ...withoutStatus } = ME;
    stubApi({ 'GET /api/v1/auth/me': { status: 200, body: withoutStatus } });

    renderProfile();

    expect(await screen.findByText('—')).toBeInTheDocument();
  });

  it('shows the stats and delete block when VITE_ACCOUNT_STATS_ENABLED is true', async () => {
    vi.stubEnv('VITE_ACCOUNT_STATS_ENABLED', 'true');
    stubApi({ 'GET /api/v1/auth/me': { status: 200, body: ME } });

    renderProfile();

    expect(await screen.findByRole('list', { name: 'Account statistics' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Request deletion' })).toBeInTheDocument();
  });

  it('saves the display name through PUT /auth/profile', async () => {
    const calls = stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'PUT /api/v1/auth/profile': { status: 200, body: { ...ME, name: 'Renamed' } },
    });
    const user = userEvent.setup();
    renderProfile();

    const input = await screen.findByLabelText('Display name');
    await user.clear(input);
    await user.type(input, 'Renamed');
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Saved');
    const put = calls.find((call) => call.method === 'PUT');
    expect(put?.body).toEqual({ name: 'Renamed' });
  });

  it('shows the server detail when saving fails', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'PUT /api/v1/auth/profile': { status: 400, body: { status: 400, detail: 'Name too long' } },
    });
    const user = userEvent.setup();
    renderProfile();

    await user.click(await screen.findByRole('button', { name: 'Save changes' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Name too long');
  });

  it('restores the saved name on Cancel', async () => {
    stubApi({ 'GET /api/v1/auth/me': { status: 200, body: ME } });
    const user = userEvent.setup();
    renderProfile();

    const input = await screen.findByLabelText('Display name');
    await user.clear(input);
    await user.type(input, 'Draft');
    await user.click(screen.getByRole('button', { name: 'Cancel' }));

    await waitFor(() => expect(input).toHaveValue('Member One'));
  });

  it('keeps a heading and offers Retry when /me fails', async () => {
    stubApi({ 'GET /api/v1/auth/me': { status: 500, body: { status: 500, detail: 'boom' } } });

    renderProfile();

    expect(await screen.findByRole('button', { name: 'Retry' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'Profile' })).toBeInTheDocument();
  });

  it('shows a role or status code it does not know as sent', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: { ...ME, role: 'AUDITOR', status: 'PENDING' } },
    });

    renderProfile();

    expect(await screen.findByText('PENDING')).toBeInTheDocument();
    expect(screen.getAllByText('AUDITOR')).toHaveLength(2);
  });

  describe('in Vietnamese', () => {
    beforeEach(async () => {
      await i18n.changeLanguage('vi');
    });
    afterEach(async () => {
      await i18n.changeLanguage('en');
    });

    it('labels the role and status in Vietnamese', async () => {
      stubApi({ 'GET /api/v1/auth/me': { status: 200, body: { ...ME, status: 'SUSPENDED' } } });

      renderProfile();

      expect(await screen.findByText(viAuth.profile.status.SUSPENDED)).toBeInTheDocument();
      expect(screen.queryByText('SUSPENDED')).not.toBeInTheDocument();
      expect(screen.getAllByText(viAuth.profile.role.MEMBER)).toHaveLength(2);
    });

    it('shows the generic retry message in Vietnamese when saving never reaches the server', async () => {
      stubApi({
        'GET /api/v1/auth/me': { status: 200, body: ME },
        'PUT /api/v1/auth/profile': () => {
          throw new TypeError('Failed to fetch');
        },
      });
      const user = userEvent.setup();
      renderProfile();

      await user.click(await screen.findByRole('button', { name: viAuth.profile.save }));

      expect(await screen.findByRole('alert')).toHaveTextContent(
        'Không thực hiện được yêu cầu. Hãy thử lại.',
      );
    });
  });
});
