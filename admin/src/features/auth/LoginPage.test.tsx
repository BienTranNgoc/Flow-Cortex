import { setToken } from '@flow-cortex/api-client';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import i18n from '../../lib/i18n';
import viAuth from '../../locales/vi/auth.json';
import viCommon from '../../locales/vi/common.json';
import { renderRoute, stubApi } from '../../test/fakeApi';
import { LoginPage } from './LoginPage';

function renderLogin() {
  return renderRoute('/login', '/login', <LoginPage />);
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

  describe('in Vietnamese', () => {
    beforeEach(async () => {
      await i18n.changeLanguage('vi');
    });
    afterEach(async () => {
      await i18n.changeLanguage('en');
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
