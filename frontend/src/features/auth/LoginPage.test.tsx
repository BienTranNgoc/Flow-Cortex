import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { setToken } from '@flow-cortex/api-client';
import { stubApi, type FakeReply } from '../../test/fakeApi';
import { AuthLayout } from './AuthLayout';
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
          <Route element={<AuthLayout />}>
            <Route path="/login" element={<LoginPage />} />
          </Route>
          <Route path="/profile" element={<p>Profile landing</p>} />
          <Route path="/workflows/7" element={<p>Workflow 7</p>} />
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
  await user.type(screen.getByLabelText('Mật khẩu'), 'secret');
  const checkbox = screen.getByRole('checkbox', { name: /Ghi nhớ đăng nhập/ });
  if (!remember) {
    await user.click(checkbox);
  }
  expect(checkbox).toHaveProperty('checked', remember);
  await user.click(screen.getByRole('button', { name: 'Đăng nhập' }));

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
    expect(screen.queryByRole('link', { name: 'Quên mật khẩu?' })).not.toBeInTheDocument();
    unmount();

    vi.stubEnv('VITE_PASSWORD_RESET_ENABLED', 'true');
    renderLogin(null);
    expect(screen.getByRole('link', { name: 'Quên mật khẩu?' })).toHaveAttribute(
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

  it('lands on the page it was sent from after a successful sign-in', async () => {
    stubApi({
      'POST /api/v1/auth/login': { status: 200, body: { accessToken: 'jwt-7', expiresIn: 900 } },
    });
    const user = userEvent.setup();
    renderLogin({ from: '/workflows/7' });

    await user.type(screen.getByLabelText('Email'), 'an@example.com');
    await user.type(screen.getByLabelText('Mật khẩu'), 'Secret123');
    await user.click(screen.getByRole('button', { name: 'Đăng nhập' }));

    expect(await screen.findByText('Workflow 7')).toBeInTheDocument();
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
      await user.type(screen.getByLabelText('Mật khẩu'), 'Secret123');
      await user.click(screen.getByRole('button', { name: 'Đăng nhập' }));
      return { calls, user };
    }

    it('asks to confirm the email and resends the link to the typed address', async () => {
      const { calls, user } = await signInUnconfirmed({
        status: 202,
        body: { status: 'confirmation_sent' },
      });

      expect(await screen.findByText('Hãy xác nhận email trước khi đăng nhập')).toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: 'Gửi lại email xác nhận' }));

      expect(
        await screen.findByText('Nếu tài khoản cần xác nhận, chúng tôi đã gửi một liên kết mới'),
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

      await user.click(await screen.findByRole('button', { name: 'Gửi lại email xác nhận' }));

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
      await user.type(screen.getByLabelText('Mật khẩu'), 'wrong-one');
      await user.click(screen.getByRole('button', { name: 'Đăng nhập' }));

      expect(await screen.findByText('Invalid email or password')).toBeInTheDocument();
      expect(
        screen.queryByRole('button', { name: 'Gửi lại email xác nhận' }),
      ).not.toBeInTheDocument();
    });
  });

  describe('on the auth shell', () => {
    const WRONG_CREDENTIALS: FakeReply = {
      status: 401,
      body: { status: 401, detail: 'Invalid email or password' },
    };

    function pose() {
      return document.querySelector('.auth-card .nodi-peek')?.getAttribute('data-pose');
    }

    async function submitWith(reply: FakeReply | (() => Promise<FakeReply>)) {
      const calls = stubApi({ 'POST /api/v1/auth/login': reply });
      const user = userEvent.setup();
      renderLogin(null);
      await user.type(screen.getByLabelText('Email'), 'an@example.com');
      await user.type(screen.getByLabelText('Mật khẩu'), 'wrong-one');
      await user.click(screen.getByRole('button', { name: 'Đăng nhập' }));
      return { calls, user };
    }

    it('greets in Vietnamese with one level-1 heading', () => {
      renderLogin(null);

      const headings = screen.getAllByRole('heading', { level: 1 });
      expect(headings).toHaveLength(1);
      expect(headings[0]).toHaveTextContent('Chào mừng đến với Nodi Studio');
      expect(screen.getByText('Đăng nhập để tiếp tục hành trình')).toBeInTheDocument();
    });

    it('shows then hides the password with a pressed-state control', async () => {
      const user = userEvent.setup();
      renderLogin(null);
      const password = screen.getByLabelText('Mật khẩu');
      await user.type(password, 's3cret-pass');
      const reveal = screen.getByRole('button', { name: 'Hiện mật khẩu' });

      expect(password).toHaveAttribute('type', 'password');
      expect(reveal).toHaveAttribute('aria-pressed', 'false');

      await user.click(reveal);
      expect(password).toHaveAttribute('type', 'text');
      expect(password).toHaveValue('s3cret-pass');
      expect(reveal).toHaveAttribute('aria-pressed', 'true');

      await user.click(reveal);
      expect(password).toHaveAttribute('type', 'password');
      expect(reveal).toHaveAttribute('aria-pressed', 'false');
    });

    it('covers its eyes on password focus and looks at the email field', async () => {
      const user = userEvent.setup();
      renderLogin(null);
      expect(pose()).toBe('idle');

      await user.click(screen.getByLabelText('Mật khẩu'));
      expect(pose()).toBe('shy');

      await user.click(screen.getByLabelText('Email'));
      expect(pose()).toBe('look');
    });

    it('on wrong credentials alerts above the fields, marks and focuses the password', async () => {
      await submitWith(WRONG_CREDENTIALS);

      const alert = await screen.findByRole('alert');
      expect(alert).toHaveTextContent('Invalid email or password');
      const email = screen.getByLabelText('Email');
      const password = screen.getByLabelText('Mật khẩu');
      expect(alert.compareDocumentPosition(email) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      expect(password).toHaveAttribute('aria-invalid', 'true');
      expect(password).toHaveClass('auth-input--shake');
      await waitFor(() => expect(password).toHaveFocus());
      expect(screen.getByRole('button', { name: 'Đăng nhập' })).toBeEnabled();
      expect(pose()).toBe('worried');
    });

    it('offers to create an account inside every wrong-credentials alert', async () => {
      await submitWith(WRONG_CREDENTIALS);

      const alert = await screen.findByRole('alert');
      expect(alert).toHaveTextContent('Chưa có tài khoản? Tạo tài khoản');
      expect(within(alert).getByRole('link', { name: 'Tạo tài khoản' })).toHaveAttribute(
        'href',
        '/register',
      );
    });

    it('offers the same create-account link to an email that has no account', async () => {
      stubApi({ 'POST /api/v1/auth/login': WRONG_CREDENTIALS });
      const user = userEvent.setup();
      renderLogin(null);
      await user.type(screen.getByLabelText('Email'), 'moi@example.com');
      await user.type(screen.getByLabelText('Mật khẩu'), 'any-password');
      await user.click(screen.getByRole('button', { name: 'Đăng nhập' }));

      const alert = await screen.findByRole('alert');
      expect(alert).toHaveTextContent(
        /^Invalid email or password\s*Chưa có tài khoản\? Tạo tài khoản$/,
      );
      expect(within(alert).getByRole('link', { name: 'Tạo tài khoản' })).toHaveAttribute(
        'href',
        '/register',
      );
    });

    it('stays worried when the password is focused after a failure', async () => {
      const { user } = await submitWith(WRONG_CREDENTIALS);
      await screen.findByRole('alert');

      await user.click(screen.getByLabelText('Mật khẩu'));

      expect(pose()).toBe('worried');
    });

    it('clears the failure once the visitor edits a field', async () => {
      const { user } = await submitWith(WRONG_CREDENTIALS);
      await screen.findByRole('alert');

      await user.type(screen.getByLabelText('Mật khẩu'), 'x');

      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
      expect(screen.getByLabelText('Mật khẩu')).toHaveAttribute('aria-invalid', 'false');
      expect(pose()).toBe('shy');
    });

    it('with Google on, points to the Google button above it and inside the alert', async () => {
      vi.stubEnv('VITE_OAUTH_ENABLED', 'true');
      await submitWith(WRONG_CREDENTIALS);

      const alert = await screen.findByRole('alert');
      expect(alert).toHaveTextContent('Đăng ký bằng Google? Dùng nút Google bên dưới');
      const hints = screen.getAllByText('Đăng ký bằng Google? Dùng nút Google bên dưới');
      const cardHint = hints.find((hint) => !alert.contains(hint));
      const google = screen.getByRole('link', { name: 'Tiếp tục với Google' });
      expect(cardHint).toBeDefined();
      expect(
        cardHint!.compareDocumentPosition(google) & Node.DOCUMENT_POSITION_FOLLOWING,
      ).toBeTruthy();
    });

    it('with Google off, mentions Google nowhere on the card', async () => {
      await submitWith(WRONG_CREDENTIALS);
      await screen.findByRole('alert');

      expect(document.querySelector('.auth-card')?.textContent).not.toMatch(/Google/);
      expect(screen.getAllByRole('link', { name: 'Tạo tài khoản' })).toHaveLength(2);
    });

    describe('when sign-in fails for another reason than wrong credentials', () => {
      const RETRY_LATER = 'Không đăng nhập được lúc này. Vui lòng thử lại sau ít phút.';

      async function expectRetryAlert(
        reply: FakeReply | (() => Promise<FakeReply>),
        message: string,
      ) {
        vi.stubEnv('VITE_OAUTH_ENABLED', 'true');
        await submitWith(reply);

        const alert = await screen.findByRole('alert');
        expect(alert).toHaveTextContent(new RegExp(`^${message}$`));
        expect(within(alert).queryByRole('link', { name: 'Tạo tài khoản' })).toBeNull();
        expect(alert).not.toHaveTextContent('Google');
        expect(screen.getByLabelText('Mật khẩu')).toHaveAttribute('aria-invalid', 'false');
        expect(screen.getByLabelText('Mật khẩu')).not.toHaveAttribute(
          'aria-describedby',
          'login-alert',
        );
        expect(screen.getByRole('button', { name: 'Đăng nhập' })).toBeEnabled();
      }

      it('on a server error with no body asks to try again later', async () => {
        await expectRetryAlert({ status: 500 }, RETRY_LATER);
      });

      it('on too many attempts shows the server detail', async () => {
        await expectRetryAlert(
          { status: 429, body: { status: 429, detail: 'Too many sign-in attempts' } },
          'Too many sign-in attempts',
        );
      });

      it('on a locked account shows the server detail', async () => {
        await expectRetryAlert(
          { status: 423, body: { status: 423, detail: 'Account is locked' } },
          'Account is locked',
        );
      });

      it('with no network asks to try again later', async () => {
        await expectRetryAlert(() => Promise.reject(new TypeError('Failed to fetch')), RETRY_LATER);
      });
    });

    it('for an unconfirmed email puts the resend form under the alert and focuses it', async () => {
      await submitWith({
        status: 403,
        body: {
          status: 403,
          detail: 'Email address is not confirmed',
          code: 'EMAIL_NOT_CONFIRMED',
        },
      });

      const alert = await screen.findByRole('alert');
      expect(alert).toHaveTextContent('Hãy xác nhận email trước khi đăng nhập');
      const resendForm = screen.getByRole('form', { name: 'Gửi lại xác nhận' });
      expect(alert.nextElementSibling).toBe(resendForm);
      await waitFor(() =>
        expect(
          within(resendForm).getByRole('button', { name: 'Gửi lại email xác nhận' }),
        ).toHaveFocus(),
      );
      expect(screen.getByLabelText('Mật khẩu')).toHaveAttribute('aria-invalid', 'false');
      expect(pose()).toBe('worried');
    });

    it('while signing in disables the fields and the button and sends nothing more', async () => {
      const { calls, user } = await submitWith(() => new Promise<FakeReply>(() => {}));

      const button = await screen.findByRole('button', { name: 'Đang đăng nhập…' });
      expect(button).toBeDisabled();
      expect(button).toHaveAttribute('aria-busy', 'true');
      expect(screen.getByLabelText('Email')).toBeDisabled();
      expect(screen.getByLabelText('Mật khẩu')).toBeDisabled();
      expect(pose()).toBe('happy');

      await user.click(button);

      expect(calls.filter((call) => call.path === '/api/v1/auth/login')).toHaveLength(1);
    });
  });

  describe('Continue with Google', () => {
    beforeEach(() => {
      vi.stubEnv('VITE_OAUTH_ENABLED', 'true');
    });

    function authorizeLink() {
      const href = screen.getByRole('link', { name: 'Tiếp tục với Google' }).getAttribute('href');
      const url = new URL(href ?? '', 'http://localhost');
      return { path: url.pathname, params: Object.fromEntries(url.searchParams) };
    }

    it('starts the authorize flow with the checkbox state and the page login returns to', async () => {
      const user = userEvent.setup();
      renderLogin({ from: '/profile' });

      expect(authorizeLink()).toEqual({
        path: '/api/v1/auth/oauth/google/authorize',
        params: { remember_me: 'true', return_to: '/profile' },
      });

      await user.click(screen.getByRole('checkbox', { name: /Ghi nhớ đăng nhập/ }));

      expect(authorizeLink().params).toEqual({ remember_me: 'false', return_to: '/profile' });
    });

    it('returns to "/" when login has no page to return to', () => {
      renderLogin(null);

      expect(authorizeLink().params.return_to).toBe('/');
    });
  });

  describe('oauth_error', () => {
    it.each([
      ['cancelled', 'Bạn đã huỷ đăng nhập bằng Google.'],
      ['invalid_state', 'Phiên đăng nhập Google đã hết hạn hoặc không hợp lệ. Vui lòng thử lại.'],
      ['registration_closed', 'Hiện chưa mở đăng ký nên không thể tạo tài khoản mới bằng Google.'],
      ['link_required', 'Email này đã có tài khoản dùng mật khẩu — hãy đăng nhập bằng mật khẩu.'],
      ['account_inactive', 'Tài khoản này hiện không hoạt động. Vui lòng liên hệ bộ phận hỗ trợ.'],
      ['rate_limited', 'Bạn đã thử quá nhiều lần. Vui lòng thử lại sau.'],
      ['failed', 'Đăng nhập bằng Google không thành công. Vui lòng thử lại.'],
      ['something_new', 'Đăng nhập bằng Google không thành công. Vui lòng thử lại.'],
    ])('shows a message for %s', (code, message) => {
      renderLogin(null, `?oauth_error=${code}`);

      expect(screen.getByRole('alert')).toHaveTextContent(message);
    });

    it('drops the Google message after the first submit, even once the failure is edited away', async () => {
      stubApi({
        'POST /api/v1/auth/login': {
          status: 401,
          body: { status: 401, detail: 'Invalid email or password' },
        },
      });
      const user = userEvent.setup();
      renderLogin(null, '?oauth_error=cancelled');
      expect(screen.getByRole('alert')).toHaveTextContent('Bạn đã huỷ đăng nhập bằng Google.');

      await user.type(screen.getByLabelText('Email'), 'an@example.com');
      await user.type(screen.getByLabelText('Mật khẩu'), 'wrong-one');
      await user.click(screen.getByRole('button', { name: 'Đăng nhập' }));
      expect(await screen.findByText('Invalid email or password')).toBeInTheDocument();

      await user.type(screen.getByLabelText('Mật khẩu'), 'x');

      expect(screen.queryByText('Bạn đã huỷ đăng nhập bằng Google.')).not.toBeInTheDocument();
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });

    it('shows no alert without the parameter', () => {
      renderLogin(null);

      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });
  });
});
