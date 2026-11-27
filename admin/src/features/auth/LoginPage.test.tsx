import { getToken, isTokenPersistent, setToken } from '@flow-cortex/api-client';
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import i18n from '../../lib/i18n';
import viAuth from '../../locales/vi/auth.json';
import viCommon from '../../locales/vi/common.json';
import { renderRoute, stubApi, testQueryClient } from '../../test/fakeApi';
import { LoginPage } from './LoginPage';

function renderLogin() {
  return renderRoute('/login', '/login', <LoginPage />);
}

function renderLoginFrom(from?: string) {
  render(
    <QueryClientProvider client={testQueryClient()}>
      <MemoryRouter initialEntries={[{ pathname: '/login', state: from ? { from } : null }]}>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/runs" element={<p>runs page</p>} />
          <Route path="/users" element={<p>users page</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('LoginPage', () => {
  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
  });

  it('offers the language toggle before sign-in', () => {
    renderLogin();

    expect(screen.getByRole('group', { name: 'Language' })).toBeInTheDocument();
  });

  it('still shows the server detail when one arrives', async () => {
    stubApi({
      'POST /api/v1/auth/login': {
        status: 401,
        body: { status: 401, detail: 'Invalid email or password' },
      },
    });
    const user = userEvent.setup();
    renderLogin();

    await user.type(screen.getByLabelText('Email'), 'admin@flowcortex.dev');
    await user.type(screen.getByLabelText('Password'), 'wrong-password');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid email or password');
  });

  it('leaves the sign-in page for the default landing page once a token is stored', () => {
    setToken('stored-token', false);
    renderLoginFrom();

    expect(screen.getByText('runs page')).toBeInTheDocument();
  });

  it('returns to the page that sent the visitor here', () => {
    setToken('stored-token', false);
    renderLoginFrom('/users');

    expect(screen.getByText('users page')).toBeInTheDocument();
  });

  it('leaves the sign-in page after a successful sign-in', async () => {
    stubApi({
      'POST /api/v1/auth/login': { status: 200, body: { accessToken: 'fresh-token' } },
    });
    const user = userEvent.setup();
    renderLoginFrom();

    await user.type(screen.getByLabelText('Email'), 'admin@flowcortex.dev');
    await user.type(screen.getByLabelText('Password'), 'secret');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByText('runs page')).toBeInTheDocument();
  });

  describe('code step', () => {
    const CHALLENGE = { status: 202, body: { status: 'TOTP_REQUIRED', expiresIn: 300 } };
    const TOTP_PATH = 'POST /api/v1/auth/login/totp';

    async function reachCodeStep(user: ReturnType<typeof userEvent.setup>) {
      await user.type(screen.getByLabelText('Email'), 'admin@flowcortex.dev');
      await user.type(screen.getByLabelText('Password'), 'secret');
      await user.click(screen.getByRole('button', { name: 'Sign in' }));
      return screen.findByRole('heading', { name: 'Enter your code' });
    }

    it('asks for the code on a 202 and stores no token', async () => {
      stubApi({ 'POST /api/v1/auth/login': CHALLENGE });
      renderLoginFrom();

      expect(await reachCodeStep(userEvent.setup())).toBeInTheDocument();
      expect(screen.queryByLabelText('Password')).not.toBeInTheDocument();
      expect(screen.getByLabelText('Authentication or recovery code')).toBeInTheDocument();
      expect(getToken()).toBeNull();
    });

    it('opens the console with the token of the completed code, kept as the server says', async () => {
      const calls = stubApi({
        'POST /api/v1/auth/login': CHALLENGE,
        [TOTP_PATH]: {
          status: 200,
          body: { accessToken: 'verified-token', expiresIn: 900, persistent: true },
        },
      });
      const user = userEvent.setup();
      renderLoginFrom();
      await reachCodeStep(user);

      await user.type(screen.getByLabelText('Authentication or recovery code'), '123 456');
      await user.click(screen.getByRole('button', { name: 'Verify' }));

      expect(await screen.findByText('runs page')).toBeInTheDocument();
      expect(getToken()).toBe('verified-token');
      expect(isTokenPersistent()).toBe(true);
      expect(calls.find((call) => call.path === '/api/v1/auth/login/totp')?.body).toEqual({
        code: '123 456',
      });
    });

    it('takes a recovery code as typed', async () => {
      const calls = stubApi({
        'POST /api/v1/auth/login': CHALLENGE,
        [TOTP_PATH]: {
          status: 200,
          body: { accessToken: 'verified-token', expiresIn: 900, persistent: false },
        },
      });
      const user = userEvent.setup();
      renderLoginFrom();
      await reachCodeStep(user);

      await user.type(screen.getByLabelText('Authentication or recovery code'), 'abcde-23456');
      await user.click(screen.getByRole('button', { name: 'Verify' }));

      expect(await screen.findByText('runs page')).toBeInTheDocument();
      expect(calls.find((call) => call.path === '/api/v1/auth/login/totp')?.body).toEqual({
        code: 'abcde-23456',
      });
    });

    it('refuses a code of the wrong shape before calling the server', async () => {
      const calls = stubApi({ 'POST /api/v1/auth/login': CHALLENGE });
      const user = userEvent.setup();
      renderLoginFrom();
      await reachCodeStep(user);

      await user.type(screen.getByLabelText('Authentication or recovery code'), '12a456');
      await user.click(screen.getByRole('button', { name: 'Verify' }));

      expect(await screen.findByRole('alert')).toHaveTextContent(
        'Enter the 6-digit code or a recovery code.',
      );
      expect(calls.some((call) => call.path === '/api/v1/auth/login/totp')).toBe(false);
    });

    it('says a wrong code is not valid, empties the field and lets the next try through', async () => {
      let attempt = 0;
      stubApi({
        'POST /api/v1/auth/login': CHALLENGE,
        [TOTP_PATH]: () => {
          attempt += 1;
          return attempt === 1
            ? { status: 422, body: { status: 422, code: 'TOTP_INVALID_CODE' } }
            : {
                status: 200,
                body: { accessToken: 'verified-token', expiresIn: 900, persistent: true },
              };
        },
      });
      const user = userEvent.setup();
      renderLoginFrom();
      await reachCodeStep(user);
      const field = screen.getByLabelText('Authentication or recovery code');

      await user.type(field, '000000');
      await user.click(screen.getByRole('button', { name: 'Verify' }));
      expect(await screen.findByRole('alert')).toHaveTextContent('The code is not valid.');
      expect(field).toHaveValue('');

      await user.type(field, '123456');
      await user.click(screen.getByRole('button', { name: 'Verify' }));
      expect(await screen.findByText('runs page')).toBeInTheDocument();
    });

    it('shows when a lock ends and keeps submit off until then', async () => {
      const lockedUntil = new Date(Date.now() + 15 * 60_000).toISOString();
      stubApi({
        'POST /api/v1/auth/login': CHALLENGE,
        [TOTP_PATH]: { status: 423, body: { status: 423, code: 'TOTP_LOCKED', lockedUntil } },
      });
      const user = userEvent.setup();
      renderLoginFrom();
      await reachCodeStep(user);

      await user.type(screen.getByLabelText('Authentication or recovery code'), '123456');
      await user.click(screen.getByRole('button', { name: 'Verify' }));

      const time = new Intl.DateTimeFormat('en-US', {
        dateStyle: 'short',
        timeStyle: 'short',
      }).format(new Date(lockedUntil));
      expect(await screen.findByRole('alert')).toHaveTextContent(
        `Too many wrong codes. Try again after ${time}.`,
      );
      expect(screen.getByRole('button', { name: 'Verify' })).toBeDisabled();
    });

    it('turns an expired step into a way back to the password form', async () => {
      stubApi({
        'POST /api/v1/auth/login': CHALLENGE,
        [TOTP_PATH]: { status: 401, body: { status: 401, code: 'TOTP_CHALLENGE_INVALID' } },
      });
      const user = userEvent.setup();
      renderLoginFrom();
      await reachCodeStep(user);

      await user.type(screen.getByLabelText('Authentication or recovery code'), '123456');
      await user.click(screen.getByRole('button', { name: 'Verify' }));

      expect(await screen.findByRole('alert')).toHaveTextContent(
        'This sign-in step has expired. Sign in again.',
      );
      expect(screen.queryByLabelText('Authentication or recovery code')).not.toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: 'Back to sign in' }));
      expect(await screen.findByLabelText('Password')).toBeInTheDocument();
    });

    it('shows the step in Vietnamese', async () => {
      await i18n.changeLanguage('vi');
      try {
        stubApi({ 'POST /api/v1/auth/login': CHALLENGE });
        const user = userEvent.setup();
        renderLoginFrom();
        await user.type(screen.getByLabelText(viAuth.login.email), 'admin@flowcortex.dev');
        await user.type(screen.getByLabelText(viAuth.login.password), 'secret');
        await user.click(screen.getByRole('button', { name: viAuth.login.submit }));

        expect(
          await screen.findByRole('heading', { name: viAuth.totp.signIn.title }),
        ).toBeInTheDocument();
        expect(screen.getByLabelText(viAuth.totp.signIn.code)).toBeInTheDocument();
        expect(screen.getByRole('button', { name: viAuth.totp.signIn.submit })).toBeInTheDocument();
      } finally {
        await i18n.changeLanguage('en');
      }
    });
  });

  it('renders one centred card with no decorative aside', () => {
    renderLogin();

    expect(
      document.querySelector('.auth-shell__aurora, .auth-shell__orb, .auth-shell__aside-grid'),
    ).toBeNull();
    expect(document.querySelector('.auth-shell__card')).not.toBeNull();
  });

  describe('in Vietnamese', () => {
    beforeEach(async () => {
      await i18n.changeLanguage('vi');
    });
    afterEach(async () => {
      await i18n.changeLanguage('en');
    });

    it('renders the auth shell and its language toggle in Vietnamese', () => {
      renderLogin();

      expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(viAuth.login.title);
      expect(screen.getByText(viAuth.login.eyebrow)).toBeInTheDocument();
      expect(screen.getByText(viAuth.login.subtitle)).toBeInTheDocument();
      expect(screen.getByText(viAuth.login.pitch.title)).toBeInTheDocument();
      expect(screen.getByText(viAuth.login.pitch.mediaJobs)).toBeInTheDocument();
      expect(screen.getByText(viAuth.login.asideFoot)).toBeInTheDocument();
      expect(screen.getByRole('form', { name: viAuth.login.title })).toBeInTheDocument();
      expect(screen.getByRole('link', { name: viAuth.login.contactOwner })).toBeInTheDocument();
      expect(screen.getByRole('group', { name: viCommon.language.groupLabel })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /^VI/ })).toHaveAttribute('aria-pressed', 'true');
    });

    it('shows the required-field messages in Vietnamese', async () => {
      const user = userEvent.setup();
      renderLogin();

      await user.click(screen.getByRole('button', { name: viAuth.login.submit }));

      const alerts = await screen.findAllByRole('alert');
      expect(alerts.map((alert) => alert.textContent)).toEqual([
        viAuth.validation.emailRequired,
        viAuth.validation.passwordRequired,
      ]);
    });

    it('shows the generic retry message when the request never reaches the server', async () => {
      stubApi({
        'POST /api/v1/auth/login': () => {
          throw new TypeError('Failed to fetch');
        },
      });
      const user = userEvent.setup();
      renderLogin();

      await user.type(screen.getByLabelText(viAuth.login.email), 'admin@flowcortex.dev');
      await user.type(screen.getByLabelText(viAuth.login.password), 'secret');
      await user.click(screen.getByRole('button', { name: viAuth.login.submit }));

      expect(await screen.findByRole('alert')).toHaveTextContent(viCommon.errors.requestFailed);
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
      renderLogin();

      await user.type(screen.getByLabelText(viAuth.login.email), 'admin@flowcortex.dev');
      await user.type(screen.getByLabelText(viAuth.login.password), 'secret');
      await user.click(screen.getByRole('button', { name: viAuth.login.submit }));

      const alert = await screen.findByRole('alert');
      expect(alert).toHaveTextContent(`Tài khoản đang tạm khóa đến ${time}. Hãy thử lại sau.`);
      expect(alert).not.toHaveTextContent(lockedUntil);
    });
  });
});
