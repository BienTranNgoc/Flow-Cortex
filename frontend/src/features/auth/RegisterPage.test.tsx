import { fireEvent, screen } from '@testing-library/react';
import { Route } from 'react-router-dom';
import { getToken, setToken } from '@flow-cortex/api-client';
import { renderRoute, stubApi, type FakeReply } from '../../test/fakeApi';
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

function renderRegister() {
  return renderRoute(
    '/register',
    '/register',
    <RegisterPage />,
    <Route path="/" element={<p>Home</p>} />,
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
    renderRoute('/register', '/register', <RegisterPage />);

    expect(screen.queryByRole('link', { name: 'Terms of Service' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Privacy Policy' })).not.toBeInTheDocument();
  });
});
