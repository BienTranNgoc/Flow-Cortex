import { setToken } from '@flow-cortex/api-client';
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
