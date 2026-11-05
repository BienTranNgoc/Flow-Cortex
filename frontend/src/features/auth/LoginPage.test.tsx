import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation, type Location } from 'react-router-dom';
import { setToken } from '@flow-cortex/api-client';
import { renderRoute, settled, stubApi, type FakeReply } from '../../test/fakeApi';
import { LoginPage } from './LoginPage';

const PERSISTENT_KEY = 'flowcortex.auth.token';
const SESSION_KEY = 'flowcortex.auth.sessionToken';

function renderLogin(state: unknown, search = '') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const result = render(
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
  return { ...result, queryClient };
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

  describe('password reset', () => {
    const FEATURES = {
      emailConfirmation: false,
      phoneVerification: false,
      uploadContentCheck: false,
      runCancellation: false,
      credits: false,
    };

    function featuresWith(passwordReset: boolean): Record<string, FakeReply> {
      return {
        'GET /api/v1/features': { status: 200, body: { ...FEATURES, passwordReset } },
      };
    }

    it('links "Forgot password?" to /forgot-password while the backend offers reset', async () => {
      stubApi(featuresWith(true));
      renderLogin(null);

      expect(await screen.findByRole('link', { name: 'Forgot password?' })).toHaveAttribute(
        'href',
        '/forgot-password',
      );
    });

    it('keeps "Forgot password?" as disabled text, not a link, while reset is switched off', async () => {
      stubApi(featuresWith(false));
      const { queryClient } = renderLogin(null);
      await settled(queryClient);

      expect(screen.queryByRole('link', { name: 'Forgot password?' })).not.toBeInTheDocument();
      expect(screen.getByText('Forgot password?')).toHaveClass('forgot-password-link--disabled');
    });

    it('shows the notice it was sent with and prefills the email over the remembered one', () => {
      window.localStorage.setItem('flowcortex.auth.rememberedEmail', 'someone@else.dev');
      const notice =
        'Password updated. Sign in with your new password. We signed you out on every device. A device that was already open may stay signed in for up to 15 minutes.';

      renderLogin({ notice, email: 'an@example.com' });

      expect(screen.getByRole('status')).toHaveTextContent(notice);
      expect(screen.getByLabelText('Email')).toHaveValue('an@example.com');
    });

    const NOTICE =
      'Password updated. Sign in with your new password. We signed you out on every device. A device that was already open may stay signed in for up to 15 minutes.';

    function renderWithNotice(state: unknown) {
      let current: Location | undefined;
      function LocationProbe() {
        current = useLocation();
        return null;
      }
      const result = renderRoute(
        '/login',
        { pathname: '/login', state },
        <>
          <LoginPage />
          <LocationProbe />
        </>,
      );
      return { ...result, location: () => current };
    }

    it('drops the notice on a reload of the page and keeps the prefilled email', async () => {
      stubApi(featuresWith(true));
      const first = renderWithNotice({ notice: NOTICE, email: 'an@example.com' });
      expect(screen.getByRole('status')).toHaveTextContent(NOTICE);
      await waitFor(() => expect(first.location()?.state).not.toHaveProperty('notice'));
      const stateAfterFirstVisit = first.location()?.state;
      first.unmount();

      renderWithNotice(stateAfterFirstVisit);

      expect(screen.queryByText(NOTICE)).not.toBeInTheDocument();
      expect(screen.getByLabelText('Email')).toHaveValue('an@example.com');
    });

    it('drops the notice after a failed sign-in and keeps the prefilled email', async () => {
      stubApi({
        ...featuresWith(true),
        'POST /api/v1/auth/login': {
          status: 401,
          body: { status: 401, detail: 'Invalid email or password' },
        },
      });
      const user = userEvent.setup();
      renderWithNotice({ notice: NOTICE, email: 'an@example.com' });
      expect(screen.getByRole('status')).toHaveTextContent(NOTICE);

      await user.type(screen.getByLabelText('Password'), 'wrong-one');
      await user.click(screen.getByRole('button', { name: /Sign in to Studio/ }));

      expect(await screen.findByText('Invalid email or password')).toBeInTheDocument();
      expect(screen.queryByText(NOTICE)).not.toBeInTheDocument();
      expect(screen.getByLabelText('Email')).toHaveValue('an@example.com');
    });

    async function signInLocked(passwordReset: boolean) {
      const lockedUntil = new Date(2026, 9, 19, 14, 35).toISOString();
      stubApi({
        ...featuresWith(passwordReset),
        'POST /api/v1/auth/login': {
          status: 423,
          body: { status: 423, detail: 'Account is temporarily locked', lockedUntil },
        },
      });
      const user = userEvent.setup();
      const { queryClient } = renderLogin(null);
      await settled(queryClient);
      await user.type(screen.getByLabelText('Email'), 'an@example.com');
      await user.type(screen.getByLabelText('Password'), 'wrong-one');
      await user.click(screen.getByRole('button', { name: /Sign in to Studio/ }));
      return screen.findByRole('alert');
    }

    it('tells a locked account when to retry and links the reset while it is offered', async () => {
      const alert = await signInLocked(true);

      expect(alert).toHaveTextContent(
        'Too many attempts. Try again at 14:35, or reset your password.',
      );
      expect(within(alert).getByRole('link', { name: 'reset your password' })).toHaveAttribute(
        'href',
        '/forgot-password',
      );
    });

    it('tells a locked account when to retry without a reset link while reset is off', async () => {
      const alert = await signInLocked(false);

      expect(alert).toHaveTextContent(/^Too many attempts\. Try again at 14:35\.$/);
      expect(within(alert).queryByRole('link')).not.toBeInTheDocument();
    });
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
});
