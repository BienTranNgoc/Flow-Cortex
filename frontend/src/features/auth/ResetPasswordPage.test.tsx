import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, useLocation, useNavigate } from 'react-router-dom';
import { renderRoute, stubApi, type Handler } from '../../test/fakeApi';
import { ResetPasswordPage } from './ResetPasswordPage';

const FEATURES = {
  emailConfirmation: false,
  phoneVerification: false,
  uploadContentCheck: false,
  runCancellation: false,
  credits: false,
  passwordReset: true,
};

const RESET = 'POST /api/v1/auth/reset-password';

const NOTICE =
  'Password updated. Sign in with your new password. We signed you out on every device. A device that was already open may stay signed in for up to 15 minutes.';

function CurrentUrl() {
  const { pathname, hash } = useLocation();
  return <div data-testid="url">{pathname + hash}</div>;
}

function OpenLink({ to }: { to: string }) {
  const navigate = useNavigate();
  return (
    <button type="button" onClick={() => navigate(to)}>
      open link
    </button>
  );
}

function LoginProbe() {
  const { state } = useLocation();
  return <div data-testid="login-state">{JSON.stringify(state)}</div>;
}

function renderPage(routes: Record<string, Handler>, url = '/reset-password#token=abc') {
  const calls = stubApi({
    'GET /api/v1/features': { status: 200, body: FEATURES },
    ...routes,
  });
  renderRoute(
    '/reset-password',
    url,
    <>
      <ResetPasswordPage />
      <CurrentUrl />
    </>,
    <Route path="/login" element={<LoginProbe />} />,
  );
  return { calls, user: userEvent.setup() };
}

function resetCalls(calls: { path: string }[]) {
  return calls.filter((call) => call.path === '/api/v1/auth/reset-password');
}

async function choose(user: ReturnType<typeof userEvent.setup>, password: string, confirm: string) {
  await user.type(await screen.findByLabelText('New password'), password);
  await user.type(screen.getByLabelText('Confirm password'), confirm);
  await user.click(screen.getByRole('button', { name: 'Set new password' }));
}

describe('ResetPasswordPage', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('refuses a password under 8 characters at its field', async () => {
    const { calls, user } = renderPage({});

    await choose(user, 'Short1', 'Short1');

    expect(await screen.findByText('Password must be at least 8 characters')).toHaveAttribute(
      'role',
      'alert',
    );
    expect(resetCalls(calls)).toEqual([]);
  });

  it('refuses a confirmation that does not match without sending anything', async () => {
    const { calls, user } = renderPage({});

    await choose(user, 'NewSecret8', 'NewSecret9');

    expect(await screen.findByText('Passwords do not match')).toHaveAttribute('role', 'alert');
    expect(resetCalls(calls)).toEqual([]);
  });

  it('offers a new link when the server refuses the link', async () => {
    const { user } = renderPage({
      [RESET]: {
        status: 422,
        body: {
          status: 422,
          detail: 'This reset link is no longer valid',
          code: 'RESET_LINK_INVALID',
        },
      },
    });

    await choose(user, 'NewSecret8', 'NewSecret8');

    expect(await screen.findByText('This reset link is no longer valid')).toHaveAttribute(
      'role',
      'alert',
    );
    expect(screen.getByLabelText('Email')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Send a new link' })).toBeInTheDocument();
    expect(screen.queryByLabelText('New password')).not.toBeInTheDocument();
  });

  it('takes a newer link opened in the same tab after a refused one', async () => {
    let answered = 0;
    const calls = stubApi({
      'GET /api/v1/features': { status: 200, body: FEATURES },
      [RESET]: () =>
        answered++ === 0
          ? {
              status: 422,
              body: {
                status: 422,
                detail: 'This reset link is no longer valid',
                code: 'RESET_LINK_INVALID',
              },
            }
          : { status: 200, body: { status: 'password_reset', email: 'an@example.com' } },
    });
    renderRoute(
      '/reset-password',
      '/reset-password#token=older',
      <>
        <ResetPasswordPage />
        <OpenLink to="/reset-password#token=newer" />
      </>,
      <Route path="/login" element={<LoginProbe />} />,
    );
    const user = userEvent.setup();

    await choose(user, 'NewSecret8', 'NewSecret8');
    await screen.findByText('This reset link is no longer valid');
    await user.click(screen.getByRole('button', { name: 'open link' }));
    await choose(user, 'NewSecret8', 'NewSecret8');

    expect(await screen.findByTestId('login-state')).toBeInTheDocument();
    expect(
      calls
        .filter((call) => call.path === '/api/v1/auth/reset-password')
        .map((call) => (call.body as { token: string }).token),
    ).toEqual(['older', 'newer']);
  });

  it('moves focus to the invalid-link message when the server refuses the link', async () => {
    const { user } = renderPage({
      [RESET]: {
        status: 422,
        body: {
          status: 422,
          detail: 'This reset link is no longer valid',
          code: 'RESET_LINK_INVALID',
        },
      },
    });

    await choose(user, 'NewSecret8', 'NewSecret8');

    const message = await screen.findByText('This reset link is no longer valid');
    expect(document.activeElement).toBe(message);
  });

  it('treats a page opened without a token as an invalid link, without calling the API', async () => {
    const { calls } = renderPage({}, '/reset-password');

    expect(await screen.findByText('This reset link is no longer valid')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Send a new link' })).toBeInTheDocument();
    expect(resetCalls(calls)).toEqual([]);
  });

  it('removes the token from the address bar and still submits it', async () => {
    const { calls, user } = renderPage({
      [RESET]: { status: 200, body: { status: 'password_reset', email: 'an@example.com' } },
    });

    expect(await screen.findByTestId('url')).toHaveTextContent(/^\/reset-password$/);

    await choose(user, 'NewSecret8', 'NewSecret8');

    await screen.findByTestId('login-state');
    expect(resetCalls(calls)).toEqual([
      {
        method: 'POST',
        path: '/api/v1/auth/reset-password',
        body: { token: 'abc', password: 'NewSecret8' },
      },
    ]);
  });

  it('moves focus to "New password" and announces the error on an empty submit', async () => {
    const { user } = renderPage({});

    await user.click(await screen.findByRole('button', { name: 'Set new password' }));

    expect(screen.getByLabelText('New password')).toHaveFocus();
    expect(screen.getByText('Password must be at least 8 characters')).toHaveAttribute(
      'role',
      'alert',
    );
  });

  it('sends the member to sign in with the notice and the account email', async () => {
    const { user } = renderPage({
      [RESET]: { status: 200, body: { status: 'password_reset', email: 'an@example.com' } },
    });

    await choose(user, 'NewSecret8', 'NewSecret8');

    expect(JSON.parse((await screen.findByTestId('login-state')).textContent ?? '')).toEqual({
      notice: NOTICE,
      email: 'an@example.com',
    });
  });

  it('shows the server password rule at its field', async () => {
    const { user } = renderPage({
      [RESET]: {
        status: 400,
        body: {
          status: 400,
          detail: 'Validation failed',
          errors: { password: 'Password is too long' },
        },
      },
    });

    await choose(user, 'NewSecret8', 'NewSecret8');

    expect(await screen.findByText('Password is too long')).toHaveAttribute('role', 'alert');
    expect(screen.getByLabelText('New password')).toHaveFocus();
  });

  it.each([
    [1250, 'Too many reset requests. Try again in 21 minutes.'],
    [45, 'Too many reset requests. Try again in 1 minute.'],
  ])('shows the wait in minutes when submissions are rate limited (%is)', async (seconds, text) => {
    const { user } = renderPage({
      [RESET]: {
        status: 429,
        body: { status: 429, detail: 'Too many requests', retryAfterSeconds: seconds },
      },
    });

    await choose(user, 'NewSecret8', 'NewSecret8');

    expect(await screen.findByText(text)).toHaveAttribute('role', 'alert');
    expect(screen.getByLabelText('New password')).toBeInTheDocument();
  });

  it('says reset is unavailable while the switch is off', async () => {
    renderPage({
      'GET /api/v1/features': { status: 200, body: { ...FEATURES, passwordReset: false } },
    });

    expect(
      await screen.findByText('Password reset is not available right now'),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Back to sign in' })).toHaveAttribute('href', '/login');
    expect(screen.queryByLabelText('New password')).not.toBeInTheDocument();
  });
});
