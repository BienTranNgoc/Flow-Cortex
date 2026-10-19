import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { setToken } from '@flow-cortex/api-client';
import { AppShell } from '../../app/AppShell';
import i18n from '../../lib/i18n';
import viAuth from '../../locales/vi/auth.json';
import viCommon from '../../locales/vi/common.json';
import { renderRoute, stubApi, type FakeReply } from '../../test/fakeApi';
import { LoginPage } from './LoginPage';

const PERSISTENT_KEY = 'flowcortex.auth.token';
const SESSION_KEY = 'flowcortex.auth.sessionToken';

function renderLogin(state: unknown, search = '') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[{ pathname: '/login', search, state }]}>
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
    'POST /api/v1/auth/login': { status: 200, body: { accessToken: 'jwt-1', expiresIn: 900 } },
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

  it('offers an account without a password a set-password link by email', () => {
    renderLogin(null);
    expect(screen.getByRole('link', { name: 'Email me a link' })).toHaveAttribute(
      'href',
      '/set-password',
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

  it.each([true, false])('sends rememberMe=%s with the credentials', async (remember) => {
    const calls = await signIn({ remember });

    const login = calls.find((call) => call.path === '/api/v1/auth/login');
    expect(login?.body).toEqual({
      email: 'member1@flowcortex.dev',
      password: 'secret',
      rememberMe: remember,
    });
  });

  describe('an account that has not confirmed its email', () => {
    const NOT_CONFIRMED: FakeReply = {
      status: 403,
      body: {
        status: 403,
        detail: 'Email address is not confirmed',
        code: 'EMAIL_NOT_CONFIRMED',
      },
    };

    async function signInUnconfirmed(resend: FakeReply) {
      const calls = stubApi({
        'POST /api/v1/auth/login': NOT_CONFIRMED,
        'POST /api/v1/auth/resend-confirmation': resend,
      });
      const user = userEvent.setup();
      renderLogin(null);
      await user.type(screen.getByLabelText('Email'), 'an@example.com');
      await user.type(screen.getByLabelText('Password'), 'Secret123');
      await user.click(screen.getByRole('button', { name: /Sign in to Studio/ }));
      return { calls, user };
    }

    it('asks to confirm the email and resends the link to the typed address', async () => {
      const { calls, user } = await signInUnconfirmed({
        status: 202,
        body: { status: 'confirmation_sent' },
      });

      expect(await screen.findByText('Confirm your email before signing in')).toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: 'Resend confirmation email' }));

      expect(
        await screen.findByText('If an account needs confirming, we sent a new link'),
      ).toBeInTheDocument();
      expect(calls.filter((call) => call.path === '/api/v1/auth/resend-confirmation')).toEqual([
        {
          method: 'POST',
          path: '/api/v1/auth/resend-confirmation',
          body: { email: 'an@example.com' },
        },
      ]);
      expect(window.localStorage.getItem(PERSISTENT_KEY)).toBeNull();
    });

    it('shows the server detail when the resend is rate-limited', async () => {
      const { user } = await signInUnconfirmed({
        status: 429,
        body: { status: 429, detail: 'Too many registration attempts; try again later' },
      });

      await user.click(await screen.findByRole('button', { name: 'Resend confirmation email' }));

      expect(
        await screen.findByText('Too many registration attempts; try again later'),
      ).toHaveAttribute('role', 'alert');
    });

    it('offers no resend for any other refusal', async () => {
      stubApi({
        'POST /api/v1/auth/login': {
          status: 401,
          body: { status: 401, detail: 'Invalid email or password' },
        },
      });
      const user = userEvent.setup();
      renderLogin(null);
      await user.type(screen.getByLabelText('Email'), 'an@example.com');
      await user.type(screen.getByLabelText('Password'), 'wrong-one');
      await user.click(screen.getByRole('button', { name: /Sign in to Studio/ }));

      expect(await screen.findByText('Invalid email or password')).toBeInTheDocument();
      expect(
        screen.queryByRole('button', { name: 'Resend confirmation email' }),
      ).not.toBeInTheDocument();
    });
  });

  describe('Continue with Google', () => {
    beforeEach(() => {
      vi.stubEnv('VITE_OAUTH_ENABLED', 'true');
    });

    function authorizeLink() {
      const href = screen.getByRole('link', { name: 'Continue with Google' }).getAttribute('href');
      const url = new URL(href ?? '', 'http://localhost');
      return { path: url.pathname, params: Object.fromEntries(url.searchParams) };
    }

    it('offers Google as the only provider, the one the backend implements', () => {
      renderLogin({});

      expect(screen.getByRole('link', { name: 'Continue with Google' })).toBeInTheDocument();
      expect(screen.queryByRole('link', { name: /GitHub/ })).not.toBeInTheDocument();
    });

    it('starts the authorize flow with the checkbox state and the page login returns to', async () => {
      const user = userEvent.setup();
      renderLogin({ from: '/profile' });

      expect(authorizeLink()).toEqual({
        path: '/api/v1/auth/oauth/google/authorize',
        params: { remember_me: 'true', return_to: '/profile' },
      });

      await user.click(screen.getByRole('checkbox', { name: /Remember me/ }));

      expect(authorizeLink().params).toEqual({ remember_me: 'false', return_to: '/profile' });
    });

    it('returns to "/" when login has no page to return to', () => {
      renderLogin(null);

      expect(authorizeLink().params.return_to).toBe('/');
    });
  });

  describe('oauth_error', () => {
    it.each([
      ['cancelled', 'Google sign-in was cancelled.'],
      ['invalid_state', 'The Google sign-in expired or was invalid. Please try again.'],
      [
        'registration_closed',
        'Registration is closed, so a new account cannot be created with Google.',
      ],
      ['link_required', 'This email already has a password account — sign in with your password.'],
      ['account_inactive', 'This account is not active. Please contact support.'],
      ['rate_limited', 'Too many attempts. Please try again later.'],
      ['failed', 'Google sign-in failed. Please try again.'],
      ['something_new', 'Google sign-in failed. Please try again.'],
    ])('shows a message for %s', (code, message) => {
      renderLogin(null, `?oauth_error=${code}`);

      expect(screen.getByRole('alert')).toHaveTextContent(message);
    });

    it('shows no alert without the parameter', () => {
      renderLogin(null);

      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });
  });

  describe('in Vietnamese', () => {
    beforeEach(async () => {
      await i18n.changeLanguage('vi');
    });
    afterEach(async () => {
      await i18n.changeLanguage('en');
    });

    function renderLoginInShell() {
      return renderRoute(
        '/login',
        '/login',
        <AppShell>
          <LoginPage />
        </AppShell>,
      );
    }

    it('shows the email-required message in Vietnamese and follows a switch to English', async () => {
      const user = userEvent.setup();
      renderLoginInShell();

      await user.click(screen.getByRole('button', { name: viAuth.login.submit }));

      expect(await screen.findByText(viAuth.validation.emailRequired)).toHaveAttribute(
        'role',
        'alert',
      );

      await user.click(screen.getByRole('button', { name: 'EN English' }));

      expect(screen.getByText('Email is required')).toHaveAttribute('role', 'alert');
    });

    it('keeps a server error in the language it arrived in until the next submit', async () => {
      const replies: FakeReply[] = [
        { status: 401, body: { status: 401, detail: 'Email hoặc mật khẩu không đúng' } },
        { status: 401, body: { status: 401, detail: 'Invalid email or password' } },
      ];
      stubApi({ 'POST /api/v1/auth/login': () => replies.shift() ?? { status: 500 } });
      const user = userEvent.setup();
      renderLoginInShell();

      await user.type(screen.getByLabelText(viAuth.login.emailLabel), 'member1@flowcortex.dev');
      await user.type(screen.getByLabelText(viAuth.login.passwordLabel), 'wrong-password');
      await user.click(screen.getByRole('button', { name: viAuth.login.submit }));
      expect(await screen.findByRole('alert')).toHaveTextContent('Email hoặc mật khẩu không đúng');

      await user.click(screen.getByRole('button', { name: 'EN English' }));

      expect(screen.getByRole('alert')).toHaveTextContent('Email hoặc mật khẩu không đúng');

      await user.click(screen.getByRole('button', { name: 'Sign in to Studio →' }));

      expect(await screen.findByText('Invalid email or password')).toHaveAttribute('role', 'alert');
      expect(screen.queryByText('Email hoặc mật khẩu không đúng')).not.toBeInTheDocument();
    });

    it('names the lock end in vi-VN form when the account is locked', async () => {
      const lockedUntil = '2026-01-01T10:15:30Z';
      stubApi({
        'POST /api/v1/auth/login': {
          status: 423,
          body: {
            type: 'https://flow-cortex.dev/problems/account-locked',
            status: 423,
            detail: `Tài khoản đang tạm khóa đến ${lockedUntil}. Hãy đăng nhập lại sau thời điểm đó.`,
            lockedUntil,
          },
        },
      });
      const time = new Intl.DateTimeFormat('vi-VN', {
        dateStyle: 'short',
        timeStyle: 'short',
      }).format(new Date(lockedUntil));
      const user = userEvent.setup();
      renderLoginInShell();

      await user.type(screen.getByLabelText(viAuth.login.emailLabel), 'member1@flowcortex.dev');
      await user.type(screen.getByLabelText(viAuth.login.passwordLabel), 'secret');
      await user.click(screen.getByRole('button', { name: viAuth.login.submit }));

      const alert = await screen.findByRole('alert');
      expect(alert).toHaveTextContent(`Tài khoản đang tạm khóa đến ${time}. Hãy thử lại sau.`);
      expect(alert).not.toHaveTextContent(lockedUntil);
    });

    it.each<[string, () => FakeReply]>([
      [
        'the request never reaches the server',
        () => {
          throw new TypeError('Failed to fetch');
        },
      ],
      ['the problem body has no detail', () => ({ status: 500, body: { status: 500 } })],
    ])('shows the generic retry message in Vietnamese when %s', async (_case, reply) => {
      stubApi({ 'POST /api/v1/auth/login': reply });
      const user = userEvent.setup();
      renderLoginInShell();

      await user.type(screen.getByLabelText(viAuth.login.emailLabel), 'member1@flowcortex.dev');
      await user.type(screen.getByLabelText(viAuth.login.passwordLabel), 'secret');
      await user.click(screen.getByRole('button', { name: viAuth.login.submit }));

      expect(await screen.findByRole('alert')).toHaveTextContent(viCommon.errors.requestFailed);
    });
  });
});
