import { screen } from '@testing-library/react';
import { Route } from 'react-router-dom';
import { getToken, setToken } from '@flow-cortex/api-client';
import { renderRoute, stubApi } from '../../test/fakeApi';
import { RequireAuth } from './RequireAuth';

const guarded = (
  <RequireAuth>
    <p>Private page</p>
  </RequireAuth>
);
const loginRoute = <Route path="/login" element={<p>Login page</p>} />;

describe('RequireAuth', () => {
  afterEach(() => {
    setToken(null);
    window.localStorage.clear();
    window.sessionStorage.clear();
    vi.unstubAllGlobals();
  });

  it('renders the page without any call when a token is stored', () => {
    setToken('stored', true);
    const calls = stubApi({});

    renderRoute('/private', '/private', guarded, loginRoute);

    expect(screen.getByText('Private page')).toBeInTheDocument();
    expect(calls).toEqual([]);
  });

  it('gets a token from the refresh cookie before deciding to redirect', async () => {
    const calls = stubApi({
      'POST /api/v1/auth/refresh': { status: 200, body: { accessToken: 'boot', expiresIn: 900 } },
    });

    renderRoute('/private', '/private', guarded, loginRoute);

    expect(await screen.findByText('Private page')).toBeInTheDocument();
    expect(getToken()).toBe('boot');
    expect(calls.map((call) => call.path)).toEqual(['/api/v1/auth/refresh']);
  });

  it('redirects to /login after one failed attempt and does not retry', async () => {
    const calls = stubApi({
      'POST /api/v1/auth/refresh': { status: 401, body: { status: 401, detail: 'no session' } },
    });

    renderRoute('/private', '/private', guarded, loginRoute);

    expect(await screen.findByText('Login page')).toBeInTheDocument();
    expect(calls).toHaveLength(1);
  });
});
