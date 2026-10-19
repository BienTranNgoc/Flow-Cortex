import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route } from 'react-router-dom';
import { getToken, setToken } from '@flow-cortex/api-client';
import i18n from '../../lib/i18n';
import { renderRoute, stubApi } from '../../test/fakeApi';
import { SetPasswordPage } from './SetPasswordPage';

const ME_REQUIRED = {
  id: 'u-1',
  email: 'an@example.com',
  name: 'an',
  role: 'MEMBER',
  status: 'ACTIVE',
  hasPassword: false,
  passwordSetupRequired: true,
};

function renderSetPassword(url: Parameters<typeof renderRoute>[1]) {
  return renderRoute(
    '/set-password',
    url,
    <SetPasswordPage />,
    <>
      <Route path="/" element={<p>Home</p>} />
      <Route path="/login" element={<p>Login page</p>} />
    </>,
  );
}

const withSetupToken = { pathname: '/set-password', state: { passwordSetupToken: 'setup-1' } };

async function fill(
  user: ReturnType<typeof userEvent.setup>,
  options: { password?: string; confirm?: string; username?: string } = {},
) {
  const password = options.password ?? 'new-password-1';
  const confirm = options.confirm ?? password;
  const username = options.username ?? '';
  await user.type(screen.getByLabelText('First name'), 'Binh');
  await user.type(screen.getByLabelText('Last name'), 'Le');
  if (username) {
    await user.type(screen.getByLabelText('Username (optional)'), username);
  }
  await user.type(screen.getByLabelText('New password'), password);
  await user.type(screen.getByLabelText('Confirm password'), confirm);
  await user.click(screen.getByRole('button', { name: 'Save password' }));
}

describe('SetPasswordPage', () => {
  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
  });

  it('sends the setup token with the chosen identity, continues on the new session and goes home', async () => {
    setToken('jwt-confirmed', false);
    const calls = stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME_REQUIRED },
      'PUT /api/v1/me/password': { status: 200, body: { accessToken: 'jwt-new', expiresIn: 900 } },
    });
    const user = userEvent.setup();
    renderSetPassword(withSetupToken);

    await fill(user, { username: 'binh_le' });

    expect(await screen.findByText('Home')).toBeInTheDocument();
    expect(getToken()).toBe('jwt-new');
    expect(calls.filter((call) => call.method === 'PUT')).toEqual([
      {
        method: 'PUT',
        path: '/api/v1/me/password',
        body: {
          passwordSetupToken: 'setup-1',
          password: 'new-password-1',
          firstName: 'Binh',
          lastName: 'Le',
          username: 'binh_le',
        },
      },
    ]);
  });

  it('refuses passwords that differ or are too short before sending anything', async () => {
    setToken('jwt-confirmed', false);
    const calls = stubApi({ 'PUT /api/v1/me/password': { status: 200, body: {} } });
    const user = userEvent.setup();
    renderSetPassword(withSetupToken);

    await fill(user, { password: 'short', confirm: 'other' });

    expect(await screen.findByText('Password must be at least 8 characters')).toBeInTheDocument();
    expect(screen.getByText('Passwords do not match')).toBeInTheDocument();
    expect(screen.getByLabelText('New password')).toHaveAttribute('aria-invalid', 'true');
    expect(calls.filter((call) => call.method === 'PUT')).toEqual([]);
  });

  it('shows a taken username at its field', async () => {
    setToken('jwt-confirmed', false);
    stubApi({
      'PUT /api/v1/me/password': {
        status: 409,
        body: {
          status: 409,
          detail: 'This username is taken',
          errors: { username: 'This username is taken' },
        },
      },
    });
    const user = userEvent.setup();
    renderSetPassword(withSetupToken);

    await fill(user, { username: 'taken_name' });

    expect(await screen.findByText('This username is taken')).toBeInTheDocument();
    expect(screen.getByLabelText('Username (optional)')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.queryByText('Home')).not.toBeInTheDocument();
  });

  it('says so when the account already has a password and offers the workspace', async () => {
    setToken('jwt-confirmed', false);
    stubApi({
      'PUT /api/v1/me/password': {
        status: 409,
        body: { status: 409, detail: 'This account already has a password' },
      },
    });
    const user = userEvent.setup();
    renderSetPassword(withSetupToken);

    await fill(user);

    expect(await screen.findByText('This account already has a password')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Go to your workspace' })).toHaveAttribute('href', '/');
  });

  it('falls back to emailing a new link when the setup token has expired', async () => {
    setToken('jwt-confirmed', false);
    const calls = stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME_REQUIRED },
      'PUT /api/v1/me/password': {
        status: 422,
        body: { status: 422, detail: 'expired', code: 'PASSWORD_SETUP_TOKEN_INVALID' },
      },
      'POST /api/v1/auth/resend-confirmation': {
        status: 202,
        body: { status: 'confirmation_sent' },
      },
    });
    const user = userEvent.setup();
    renderSetPassword(withSetupToken);

    await fill(user);
    expect(
      await screen.findByText('This password setup has expired. Ask for a new link below.'),
    ).toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: 'Email me a set-password link' }));

    expect(
      await screen.findByText('If the account has no password yet, we sent a link'),
    ).toBeInTheDocument();
    expect(calls.filter((call) => call.path === '/api/v1/auth/resend-confirmation')).toEqual([
      {
        method: 'POST',
        path: '/api/v1/auth/resend-confirmation',
        body: { email: 'an@example.com' },
      },
    ]);
  });

  it('without a setup token offers only the email link, and no way past a required password', async () => {
    setToken('jwt-session', false);
    const calls = stubApi({ 'GET /api/v1/auth/me': { status: 200, body: ME_REQUIRED } });
    renderSetPassword('/set-password');

    expect(
      await screen.findByRole('button', { name: 'Email me a set-password link' }),
    ).toBeInTheDocument();
    expect(screen.queryByLabelText('New password')).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Go to your workspace' })).not.toBeInTheDocument();
    expect(calls.filter((call) => call.method === 'PUT')).toEqual([]);
  });

  it('lets a member held at a required password without a setup token sign out instead', async () => {
    setToken('jwt-session', false);
    const calls = stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME_REQUIRED },
      'POST /api/v1/auth/logout': { status: 204 },
    });
    const user = userEvent.setup();
    renderSetPassword('/set-password');

    await user.click(await screen.findByRole('link', { name: 'Sign out' }));

    expect(await screen.findByText('Login page')).toBeInTheDocument();
    expect(getToken()).toBeNull();
    expect(calls.filter((call) => call.path === '/api/v1/auth/logout')).toHaveLength(1);
  });

  it('offers no sign-out to a signed-in member whose password is not required', async () => {
    setToken('jwt-session', false);
    stubApi({
      'GET /api/v1/auth/me': {
        status: 200,
        body: { ...ME_REQUIRED, hasPassword: true, passwordSetupRequired: false },
      },
    });
    renderSetPassword('/set-password');

    expect(await screen.findByRole('link', { name: 'Go to your workspace' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Sign out' })).not.toBeInTheDocument();
  });

  it('opens an emailed link signed out, then asks for the password with the token it handed out', async () => {
    const calls = stubApi({
      'POST /api/v1/auth/password-link': {
        status: 200,
        body: {
          accessToken: 'jwt-link',
          expiresIn: 900,
          passwordSetupRequired: true,
          passwordSetupToken: 'setup-2',
        },
      },
      'PUT /api/v1/me/password': { status: 200, body: { accessToken: 'jwt-new', expiresIn: 900 } },
    });
    const user = userEvent.setup();
    renderSetPassword('/set-password?token=link-1');

    expect(await screen.findByLabelText('New password')).toBeInTheDocument();
    expect(getToken()).toBe('jwt-link');
    await fill(user);

    expect(await screen.findByText('Home')).toBeInTheDocument();
    expect(calls.map((call) => [call.method, call.path, call.body])).toEqual([
      ['POST', '/api/v1/auth/password-link', { token: 'link-1' }],
      [
        'PUT',
        '/api/v1/me/password',
        {
          passwordSetupToken: 'setup-2',
          password: 'new-password-1',
          firstName: 'Binh',
          lastName: 'Le',
        },
      ],
    ]);
  });

  it('says a spent link is no longer valid and lets the visitor ask for another', async () => {
    stubApi({
      'POST /api/v1/auth/password-link': {
        status: 422,
        body: {
          status: 422,
          detail: 'This set-password link is no longer valid',
          code: 'PASSWORD_LINK_INVALID',
        },
      },
    });
    renderSetPassword('/set-password?token=used-link');

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'This set-password link is no longer valid',
    );
    expect(getToken()).toBeNull();
    expect(screen.getByLabelText('Email')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Back to sign in' })).toHaveAttribute('href', '/login');
  });

  describe('in Vietnamese', () => {
    beforeEach(async () => {
      await i18n.changeLanguage('vi');
    });
    afterEach(async () => {
      await i18n.changeLanguage('en');
    });

    it('labels the form and words the password rules in Vietnamese', async () => {
      setToken('jwt-confirmed', false);
      const calls = stubApi({ 'PUT /api/v1/me/password': { status: 200, body: {} } });
      const user = userEvent.setup();
      renderSetPassword(withSetupToken);

      expect(screen.getByRole('heading', { name: 'Chọn mật khẩu của bạn' })).toBeInTheDocument();
      expect(screen.getByRole('form', { name: 'Đặt mật khẩu' })).toBeInTheDocument();
      await user.type(screen.getByLabelText('Tên'), 'Bình');
      await user.type(screen.getByLabelText('Họ'), 'Lê');
      await user.type(screen.getByLabelText('Mật khẩu mới'), 'short');
      await user.type(screen.getByLabelText('Xác nhận mật khẩu'), 'other');
      await user.click(screen.getByRole('button', { name: 'Lưu mật khẩu' }));

      expect(await screen.findByText('Mật khẩu phải có ít nhất 8 ký tự')).toBeInTheDocument();
      expect(screen.getByText('Mật khẩu xác nhận không khớp, hãy nhập lại')).toBeInTheDocument();
      expect(calls.filter((call) => call.method === 'PUT')).toEqual([]);
    });

    it('says a spent link is no longer valid and offers a new one in Vietnamese', async () => {
      stubApi({
        'POST /api/v1/auth/password-link': {
          status: 422,
          body: { status: 422, code: 'PASSWORD_LINK_INVALID' },
        },
      });
      renderSetPassword('/set-password?token=used-link');

      expect(await screen.findByRole('alert')).toHaveTextContent(
        'Liên kết đặt mật khẩu này không còn hiệu lực',
      );
      expect(
        screen.getByRole('button', { name: 'Gửi cho tôi liên kết đặt mật khẩu' }),
      ).toBeInTheDocument();
      expect(screen.getByRole('link', { name: 'Quay lại đăng nhập' })).toHaveAttribute(
        'href',
        '/login',
      );
    });
  });
});
