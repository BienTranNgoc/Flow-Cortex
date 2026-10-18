import { screen } from '@testing-library/react';
import { Route } from 'react-router-dom';
import { setToken } from '@flow-cortex/api-client';
import { renderRoute, stubApi } from '../../test/fakeApi';
import { PasswordSetupGate } from './PasswordSetupGate';

const ME = { id: 'u-1', email: 'an@example.com', name: 'An', role: 'MEMBER', status: 'ACTIVE' };

function renderGated() {
  return renderRoute(
    '/workflows',
    '/workflows',
    <PasswordSetupGate>
      <p>Workspace</p>
    </PasswordSetupGate>,
    <Route path="/set-password" element={<p>Set password page</p>} />,
  );
}

describe('PasswordSetupGate', () => {
  beforeEach(() => setToken('jwt', false));

  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
  });

  it('sends an account with no way to sign in again to set a password', async () => {
    stubApi({
      'GET /api/v1/auth/me': {
        status: 200,
        body: { ...ME, hasPassword: false, passwordSetupRequired: true },
      },
    });

    renderGated();

    expect(await screen.findByText('Set password page')).toBeInTheDocument();
    expect(screen.queryByText('Workspace')).not.toBeInTheDocument();
  });

  it('lets a Google-linked account without a password into the workspace', async () => {
    stubApi({
      'GET /api/v1/auth/me': {
        status: 200,
        body: { ...ME, hasPassword: false, passwordSetupRequired: false },
      },
    });

    renderGated();

    expect(await screen.findByText('Workspace')).toBeInTheDocument();
  });

  it('lets the page through when /me fails', async () => {
    stubApi({ 'GET /api/v1/auth/me': { status: 500, body: { status: 500, detail: 'boom' } } });

    renderGated();

    expect(await screen.findByText('Workspace')).toBeInTheDocument();
  });
});
