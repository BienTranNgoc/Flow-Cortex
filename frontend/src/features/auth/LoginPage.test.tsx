import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { setToken } from '@flow-cortex/api-client';
import { stubApi } from '../../test/fakeApi';
import { LoginPage } from './LoginPage';

const PERSISTENT_KEY = 'flowcortex.auth.token';
const SESSION_KEY = 'flowcortex.auth.sessionToken';

function renderLogin(state: unknown) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[{ pathname: '/login', state }]}>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/profile" element={<p>Profile landing</p>} />
          <Route path="/" element={<p>Home</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

async function signIn({ remember }: { remember: boolean }) {
  const calls = stubApi({
    'POST /api/v1/auth/login': { status: 200, body: { token: 'jwt-1' } },
  });
  const user = userEvent.setup();
  renderLogin({ from: '/profile' });

  await user.type(screen.getByLabelText('Email'), 'member1@flowcortex.dev');
  await user.type(screen.getByLabelText('Password'), 'secret');
  const checkbox = screen.getByRole('checkbox', { name: /Remember me/ });
  if (!remember) {
    await user.click(checkbox);
  }
  expect(checkbox).toHaveProperty('checked', remember);
  await user.click(screen.getByRole('button', { name: /Sign in to Studio/ }));

  expect(await screen.findByText('Profile landing')).toBeInTheDocument();
  return calls;
}

describe('LoginPage', () => {
  afterEach(() => {
    setToken(null);
    window.localStorage.clear();
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
  });

  it('hides "Forgot password?" until VITE_PASSWORD_RESET_ENABLED is true', () => {
    const { unmount } = renderLogin(null);
    expect(screen.queryByRole('link', { name: 'Forgot password?' })).not.toBeInTheDocument();
    unmount();

    vi.stubEnv('VITE_PASSWORD_RESET_ENABLED', 'true');
    renderLogin(null);
    expect(screen.getByRole('link', { name: 'Forgot password?' })).toHaveAttribute(
      'href',
      '/forgot-password',
    );
  });

  it('redirects to the `from` page when a token is already present', () => {
    setToken('existing-token', true);

    renderLogin({ from: '/profile' });

    expect(screen.getByText('Profile landing')).toBeInTheDocument();
  });

  it('falls back to "/" when no `from` was provided', () => {
    setToken('existing-token', true);

    renderLogin(null);

    expect(screen.getByText('Home')).toBeInTheDocument();
  });

  it('keeps the token in localStorage when "Remember me" is ticked', async () => {
    await signIn({ remember: true });

    expect(window.localStorage.getItem(PERSISTENT_KEY)).toBe('jwt-1');
    expect(window.sessionStorage.getItem(SESSION_KEY)).toBeNull();
  });

  it('keeps the token only in sessionStorage when "Remember me" is unticked', async () => {
    await signIn({ remember: false });

    expect(window.localStorage.getItem(PERSISTENT_KEY)).toBeNull();
    expect(window.sessionStorage.getItem(SESSION_KEY)).toBe('jwt-1');
  });

  it('sends only the credentials in the login request body', async () => {
    const calls = await signIn({ remember: false });

    const login = calls.find((call) => call.path === '/api/v1/auth/login');
    expect(login?.body).toEqual({ email: 'member1@flowcortex.dev', password: 'secret' });
  });
});
