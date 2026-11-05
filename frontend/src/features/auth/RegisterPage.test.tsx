import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { getToken, setToken } from '@flow-cortex/api-client';
import i18n from '../../lib/i18n';
import viAuth from '../../locales/vi/auth.json';
import { stubApi, type FakeReply } from '../../test/fakeApi';
import { AuthLayout } from './AuthLayout';
import { RegisterPage } from './RegisterPage';

interface RegistrationInput {
  firstName?: string;
  lastName?: string;
  email?: string;
  username?: string;
}

function fillAndSubmit({
  firstName = 'An',
  lastName = 'Tran',
  email = 'an@example.com',
  username = '',
}: RegistrationInput = {}) {
  fireEvent.change(screen.getByLabelText('First name'), { target: { value: firstName } });
  fireEvent.change(screen.getByLabelText('Last name'), { target: { value: lastName } });
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: email } });
  fireEvent.change(screen.getByLabelText('Username (optional)'), { target: { value: username } });
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'Secret123' } });
  fireEvent.change(screen.getByLabelText('Confirm password'), { target: { value: 'Secret123' } });
  fireEvent.click(screen.getByRole('button', { name: 'Get started free →' }));
}

function fillAndSubmitVi({
  firstName = 'An',
  lastName = 'Tran',
  email = 'an@example.com',
  username = '',
}: RegistrationInput = {}) {
  fireEvent.change(screen.getByLabelText('Tên'), { target: { value: firstName } });
  fireEvent.change(screen.getByLabelText('Họ'), { target: { value: lastName } });
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: email } });
  fireEvent.change(screen.getByLabelText('Tên người dùng (không bắt buộc)'), {
    target: { value: username },
  });
  fireEvent.change(screen.getByLabelText('Mật khẩu'), { target: { value: 'Secret123' } });
  fireEvent.change(screen.getByLabelText('Nhập lại mật khẩu'), { target: { value: 'Secret123' } });
  fireEvent.click(screen.getByRole('button', { name: 'Tạo tài khoản' }));
}

function renderRegister() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/register']}>
        <Routes>
          <Route element={<AuthLayout />}>
            <Route path="/register" element={<RegisterPage />} />
          </Route>
          <Route path="/" element={<p>Home</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const REGISTERED_BODY = {
  firstName: 'An',
  lastName: 'Tran',
  email: 'an@example.com',
  password: 'Secret123',
  rememberMe: true,
};

describe('RegisterPage', () => {
  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
  });

  it('asks for first name, last name, email, password, confirmation and an optional username', () => {
    renderRegister();

    for (const label of [
      'First name',
      'Last name',
      'Email',
      'Password',
      'Confirm password',
      'Username (optional)',
    ]) {
      expect(screen.getByLabelText(label)).toBeInTheDocument();
    }
  });

  it('refuses a whitespace-only last name at its field without sending a request', async () => {
    const calls = stubApi({});
    renderRegister();

    fillAndSubmit({ lastName: '   ' });

    expect(await screen.findByLabelText('Last name')).toHaveAccessibleDescription(
      'Last name is required',
    );
    expect(calls).toEqual([]);
  });

  it('refuses an email without @ at the email field', async () => {
    const calls = stubApi({});
    renderRegister();

    fillAndSubmit({ email: 'an.example.com' });

    expect(await screen.findByText('Enter a valid email address')).toBeInTheDocument();
    expect(screen.getByLabelText('Email')).toHaveAccessibleDescription(
      'Enter a valid email address',
    );
    expect(calls).toEqual([]);
  });

  it.each(['ab', 'An_1', 'an-1', 'a'.repeat(21)])(
    'refuses the username %s at its field without sending a request',
    async (username) => {
      const calls = stubApi({});
      renderRegister();

      fillAndSubmit({ username });

      expect(
        await screen.findByText('Use 3–20 lowercase letters, digits or _'),
      ).toBeInTheDocument();
      expect(screen.getByLabelText('Username (optional)')).toHaveAccessibleDescription(
        'Use 3–20 lowercase letters, digits or _',
      );
      expect(calls).toEqual([]);
    },
  );

  it('shows a taken username at the username field and sends the username it was given', async () => {
    const calls = stubApi({
      'POST /api/v1/auth/register': {
        status: 409,
        body: {
          status: 409,
          detail: 'This username is taken',
          errors: { username: 'This username is taken' },
        },
      },
    });
    renderRegister();

    fillAndSubmit({ username: 'an_1' });

    expect(await screen.findByText('This username is taken')).toBeInTheDocument();
    expect(screen.getByLabelText('Username (optional)')).toHaveAccessibleDescription(
      'This username is taken',
    );
    expect(screen.getAllByText('This username is taken')).toHaveLength(1);
    expect(calls).toEqual([
      {
        method: 'POST',
        path: '/api/v1/auth/register',
        body: { ...REGISTERED_BODY, username: 'an_1' },
      },
    ]);
  });

  it('shows an existing email at the email field', async () => {
    stubApi({
      'POST /api/v1/auth/register': {
        status: 409,
        body: {
          status: 409,
          detail: 'An account with this email already exists',
          errors: { email: 'An account with this email already exists' },
        },
      },
    });
    renderRegister();

    fillAndSubmit({ email: 'AN@example.com' });

    expect(
      await screen.findByText('An account with this email already exists'),
    ).toBeInTheDocument();
    expect(screen.getByLabelText('Email')).toHaveAccessibleDescription(
      'An account with this email already exists',
    );
  });

  it('says to check the inbox on 202, omits the empty username and stores no session', async () => {
    const calls = stubApi({
      'POST /api/v1/auth/register': { status: 202, body: { status: 'confirmation_sent' } },
    });
    renderRegister();

    fillAndSubmit();

    expect(await screen.findByText('Check your inbox to confirm your email')).toBeInTheDocument();
    expect(screen.queryByRole('form', { name: 'Create account' })).not.toBeInTheDocument();
    expect(getToken()).toBeNull();
    expect(calls).toEqual([
      { method: 'POST', path: '/api/v1/auth/register', body: REGISTERED_BODY },
    ]);
  });

  it('signs in and lands home when the server answers 201 with a session', async () => {
    stubApi({
      'POST /api/v1/auth/register': { status: 201, body: { accessToken: 'jwt-r', expiresIn: 900 } },
    });
    renderRegister();

    fillAndSubmit();

    expect(await screen.findByText('Home')).toBeInTheDocument();
    expect(getToken()).toBe('jwt-r');
  });

  it.each<[string, FakeReply]>([
    [
      'registration is closed (403)',
      { status: 403, body: { status: 403, detail: 'Self-registration is not available' } },
    ],
    [
      'the attempt is rate-limited (429)',
      {
        status: 429,
        body: { status: 429, detail: 'Too many registration attempts; try again later' },
      },
    ],
  ])('shows the server detail when %s and stays on the form', async (_case, reply) => {
    stubApi({ 'POST /api/v1/auth/register': reply });
    renderRegister();

    fillAndSubmit();

    const detail = (reply.body as { detail: string }).detail;
    expect(await screen.findByText(detail)).toHaveAttribute('role', 'alert');
    expect(screen.getByRole('form', { name: 'Create account' })).toBeInTheDocument();
  });

  it('starts the Google authorize flow remembered and returning to "/"', () => {
    vi.stubEnv('VITE_OAUTH_ENABLED', 'true');
    renderRegister();

    const href = screen.getByRole('link', { name: 'Sign up with Google' }).getAttribute('href');
    const url = new URL(href ?? '', 'http://localhost');
    expect(url.pathname).toBe('/api/v1/auth/oauth/google/authorize');
    expect(Object.fromEntries(url.searchParams)).toEqual({ remember_me: 'true', return_to: '/' });
  });

  it('links to no legal page the app does not serve', () => {
    renderRegister();

    expect(screen.queryByRole('link', { name: 'Terms of Service' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Privacy Policy' })).not.toBeInTheDocument();
  });

  describe('in Vietnamese', () => {
    beforeEach(async () => {
      await i18n.changeLanguage('vi');
    });
    afterEach(async () => {
      await i18n.changeLanguage('en');
      window.localStorage.clear();
    });

    it('switches to English without losing the typed name and remembers the choice', async () => {
      const user = userEvent.setup();
      renderRegister();

      await user.type(screen.getByLabelText(viAuth.register.firstNameLabel), 'An');
      await user.click(screen.getByRole('button', { name: 'EN English' }));

      expect(screen.getByLabelText('First name')).toHaveValue('An');
      expect(screen.getByLabelText('Username (optional)')).toBeInTheDocument();
      expect(screen.getByRole('form', { name: 'Create account' })).toBeInTheDocument();
      expect(window.localStorage.getItem('flow-cortex.locale')).toBe('en');
    });

    it('shows the generic retry message in Vietnamese when the request never reaches the server', async () => {
      stubApi({
        'POST /api/v1/auth/register': () => {
          throw new TypeError('Failed to fetch');
        },
      });
      renderRegister();

      fireEvent.change(screen.getByLabelText(viAuth.register.firstNameLabel), {
        target: { value: 'New' },
      });
      fireEvent.change(screen.getByLabelText(viAuth.register.lastNameLabel), {
        target: { value: 'Creator' },
      });
      fireEvent.change(screen.getByLabelText(viAuth.register.emailLabel), {
        target: { value: 'new.creator@example.com' },
      });
      fireEvent.change(screen.getByLabelText(viAuth.register.passwordLabel), {
        target: { value: 'register-password-1' },
      });
      fireEvent.change(screen.getByLabelText(viAuth.register.confirmPasswordLabel), {
        target: { value: 'register-password-1' },
      });
      fireEvent.click(screen.getByRole('button', { name: viAuth.register.submit }));

      expect(await screen.findByRole('alert')).toHaveTextContent(viAuth.register.retryLater);
    });

    it('says to check the inbox in Vietnamese on 202', async () => {
      stubApi({
        'POST /api/v1/auth/register': { status: 202, body: { status: 'confirmation_sent' } },
      });
      renderRegister();

      fireEvent.change(screen.getByLabelText(viAuth.register.firstNameLabel), {
        target: { value: 'An' },
      });
      fireEvent.change(screen.getByLabelText(viAuth.register.lastNameLabel), {
        target: { value: 'Tran' },
      });
      fireEvent.change(screen.getByLabelText(viAuth.register.emailLabel), {
        target: { value: 'an@example.com' },
      });
      fireEvent.change(screen.getByLabelText(viAuth.register.passwordLabel), {
        target: { value: 'Secret123' },
      });
      fireEvent.change(screen.getByLabelText(viAuth.register.confirmPasswordLabel), {
        target: { value: 'Secret123' },
      });
      fireEvent.click(screen.getByRole('button', { name: viAuth.register.submit }));

      expect(await screen.findByText(viAuth.register.sent.title)).toBeInTheDocument();
      expect(screen.getByRole('status')).toHaveTextContent(
        'Chúng tôi đã gửi liên kết xác nhận đến an@example.com. Hãy mở liên kết trong vòng 24 giờ để kích hoạt tài khoản; liên kết sẽ đăng nhập cho bạn và mời bạn chọn mật khẩu.',
      );
      expect(screen.getByText('an@example.com').tagName).toBe('STRONG');
      expect(
        screen.getByRole('button', { name: viAuth.confirm.resend.submit }),
      ).toBeInTheDocument();
    });

    it('asks for first name, last name, email, password, confirmation and an optional username', () => {
      renderRegister();

      for (const label of [
        'Tên',
        'Họ',
        'Email',
        'Mật khẩu',
        'Nhập lại mật khẩu',
        'Tên người dùng (không bắt buộc)',
      ]) {
        expect(screen.getByLabelText(label)).toBeInTheDocument();
      }
    });

    it('renders inside the auth shell with its mascot, banner stage and one level-1 heading', () => {
      renderRegister();

      expect(document.querySelector('.auth-card svg.nodi-peek')).not.toBeNull();
      expect(document.querySelector('.auth-shell__stage')).not.toBeNull();
      const headings = screen.getAllByRole('heading', { level: 1 });
      expect(headings).toHaveLength(1);
      expect(headings[0]).toHaveTextContent('Bắt đầu cùng Nodi Studio');
    });

    it('refuses a whitespace-only last name at its field without sending a request', async () => {
      const calls = stubApi({});
      renderRegister();

      fillAndSubmitVi({ lastName: '   ' });

      expect(await screen.findByLabelText('Họ')).toHaveAccessibleDescription('Vui lòng nhập họ');
      expect(calls).toEqual([]);
    });

    it('refuses an email without @ at the email field', async () => {
      const calls = stubApi({});
      renderRegister();

      fillAndSubmitVi({ email: 'an.example.com' });

      expect(await screen.findByText('Nhập một địa chỉ email hợp lệ')).toBeInTheDocument();
      expect(screen.getByLabelText('Email')).toHaveAccessibleDescription(
        'Nhập một địa chỉ email hợp lệ',
      );
      expect(calls).toEqual([]);
    });

    it.each(['ab', 'An_1', 'an-1', 'a'.repeat(21)])(
      'refuses the username %s at its field without sending a request',
      async (username) => {
        const calls = stubApi({});
        renderRegister();

        fillAndSubmitVi({ username });

        expect(await screen.findByText('Dùng 3–20 chữ thường, chữ số hoặc _')).toBeInTheDocument();
        expect(
          screen.getByLabelText('Tên người dùng (không bắt buộc)'),
        ).toHaveAccessibleDescription('Dùng 3–20 chữ thường, chữ số hoặc _');
        expect(calls).toEqual([]);
      },
    );

    it('shows a taken username at the username field and sends the username it was given', async () => {
      const calls = stubApi({
        'POST /api/v1/auth/register': {
          status: 409,
          body: {
            status: 409,
            detail: 'This username is taken',
            errors: { username: 'This username is taken' },
          },
        },
      });
      renderRegister();

      fillAndSubmitVi({ username: 'an_1' });

      expect(await screen.findByText('This username is taken')).toBeInTheDocument();
      expect(screen.getByLabelText('Tên người dùng (không bắt buộc)')).toHaveAccessibleDescription(
        'This username is taken',
      );
      expect(screen.getAllByText('This username is taken')).toHaveLength(1);
      expect(calls).toEqual([
        {
          method: 'POST',
          path: '/api/v1/auth/register',
          body: { ...REGISTERED_BODY, username: 'an_1' },
        },
      ]);
    });

    it('shows an existing email at the email field', async () => {
      stubApi({
        'POST /api/v1/auth/register': {
          status: 409,
          body: {
            status: 409,
            detail: 'An account with this email already exists',
            errors: { email: 'An account with this email already exists' },
          },
        },
      });
      renderRegister();

      fillAndSubmitVi({ email: 'AN@example.com' });

      expect(
        await screen.findByText('An account with this email already exists'),
      ).toBeInTheDocument();
      expect(screen.getByLabelText('Email')).toHaveAccessibleDescription(
        'An account with this email already exists',
      );
    });

    it('says to try again later when the server refuses without a message', async () => {
      stubApi({ 'POST /api/v1/auth/register': { status: 403 } });
      renderRegister();

      fillAndSubmitVi();

      expect(await screen.findByRole('alert')).toHaveTextContent(
        'Không tạo được tài khoản lúc này. Vui lòng thử lại sau ít phút.',
      );
      expect(screen.getByRole('button', { name: 'Tạo tài khoản' })).toBeEnabled();
    });

    it('says to check the inbox on 202, omits the empty username and stores no session', async () => {
      const calls = stubApi({
        'POST /api/v1/auth/register': { status: 202, body: { status: 'confirmation_sent' } },
      });
      renderRegister();

      fillAndSubmitVi();

      expect(await screen.findByText('Kiểm tra hộp thư để xác nhận email')).toBeInTheDocument();
      expect(screen.queryByRole('form', { name: 'Tạo tài khoản' })).not.toBeInTheDocument();
      expect(getToken()).toBeNull();
      expect(calls).toEqual([
        { method: 'POST', path: '/api/v1/auth/register', body: REGISTERED_BODY },
      ]);
    });

    it('signs in and lands home when the server answers 201 with a session', async () => {
      stubApi({
        'POST /api/v1/auth/register': {
          status: 201,
          body: { accessToken: 'jwt-r', expiresIn: 900 },
        },
      });
      renderRegister();

      fillAndSubmitVi();

      expect(await screen.findByText('Home')).toBeInTheDocument();
      expect(getToken()).toBe('jwt-r');
    });

    it.each<[string, FakeReply]>([
      [
        'registration is closed (403)',
        { status: 403, body: { status: 403, detail: 'Self-registration is not available' } },
      ],
      [
        'the attempt is rate-limited (429)',
        {
          status: 429,
          body: { status: 429, detail: 'Too many registration attempts; try again later' },
        },
      ],
    ])('shows the server detail when %s and stays on the form', async (_case, reply) => {
      stubApi({ 'POST /api/v1/auth/register': reply });
      renderRegister();

      fillAndSubmitVi();

      const detail = (reply.body as { detail: string }).detail;
      expect(await screen.findByText(detail)).toHaveAttribute('role', 'alert');
      expect(screen.getByRole('form', { name: 'Tạo tài khoản' })).toBeInTheDocument();
    });

    describe('mascot pose', () => {
      const pose = () => document.querySelector('.auth-card .nodi-peek')?.getAttribute('data-pose');

      it('looks at the email field and covers its eyes on both password fields', () => {
        renderRegister();

        fireEvent.focus(screen.getByLabelText('Email'));
        expect(pose()).toBe('look');
        fireEvent.focus(screen.getByLabelText('Mật khẩu'));
        expect(pose()).toBe('shy');
        fireEvent.focus(screen.getByLabelText('Nhập lại mật khẩu'));
        expect(pose()).toBe('shy');
      });

      it('worries after a refused registration and stops once a field is edited', async () => {
        stubApi({
          'POST /api/v1/auth/register': {
            status: 403,
            body: { status: 403, detail: 'Self-registration is not available' },
          },
        });
        renderRegister();

        fillAndSubmitVi();

        expect(await screen.findByText('Self-registration is not available')).toBeInTheDocument();
        expect(pose()).toBe('worried');
        fireEvent.change(screen.getByLabelText('Tên'), { target: { value: 'Ann' } });
        expect(pose()).not.toBe('worried');
      });

      it('is happy on the check-your-inbox screen', async () => {
        stubApi({
          'POST /api/v1/auth/register': { status: 202, body: { status: 'confirmation_sent' } },
        });
        renderRegister();

        fillAndSubmitVi();

        expect(await screen.findByText('Kiểm tra hộp thư để xác nhận email')).toBeInTheDocument();
        expect(pose()).toBe('happy');
      });
    });

    it('starts the Google authorize flow remembered and returning to "/"', () => {
      vi.stubEnv('VITE_OAUTH_ENABLED', 'true');
      renderRegister();

      const href = screen.getByRole('link', { name: 'Tiếp tục với Google' }).getAttribute('href');
      const url = new URL(href ?? '', 'http://localhost');
      expect(url.pathname).toBe('/api/v1/auth/oauth/google/authorize');
      expect(Object.fromEntries(url.searchParams)).toEqual({
        remember_me: 'true',
        return_to: '/',
      });
    });
  });
});
