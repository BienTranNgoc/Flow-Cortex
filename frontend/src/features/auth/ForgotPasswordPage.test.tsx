import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, stubApi, type Handler } from '../../test/fakeApi';
import { ForgotPasswordPage } from './ForgotPasswordPage';

const FEATURES = {
  emailConfirmation: false,
  phoneVerification: false,
  uploadContentCheck: false,
  runCancellation: false,
  credits: false,
  passwordReset: true,
};

const FORGOT = 'POST /api/v1/auth/forgot-password';

function renderPage(routes: Record<string, Handler>) {
  const calls = stubApi({
    'GET /api/v1/features': { status: 200, body: FEATURES },
    ...routes,
  });
  renderRoute('/forgot-password', '/forgot-password', <ForgotPasswordPage />);
  return { calls, user: userEvent.setup() };
}

async function submit(user: ReturnType<typeof userEvent.setup>, email: string) {
  await user.type(await screen.findByLabelText('Email'), email);
  await user.click(screen.getByRole('button', { name: 'Send reset link' }));
}

describe('ForgotPasswordPage', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('refuses a malformed email at its field without sending anything', async () => {
    const { calls, user } = renderPage({});

    await submit(user, 'an.example.com');

    expect(await screen.findByRole('alert')).toHaveTextContent('Enter a valid email address');
    expect(screen.getByLabelText('Email')).toHaveFocus();
    expect(calls.filter((call) => call.path === '/api/v1/auth/forgot-password')).toEqual([]);
  });

  it.each(['an@example.com', 'nobody@example.com'])(
    'repeats the typed email %s in the same answer whatever the email belongs to',
    async (email) => {
      const { calls, user } = renderPage({
        [FORGOT]: { status: 202, body: { status: 'reset_requested' } },
      });

      await submit(user, email);

      expect(await screen.findByRole('status')).toHaveTextContent(
        `If an account exists for ${email}, we sent a reset link. Didn't get it? Check spam or try again.`,
      );
      expect(calls.filter((call) => call.path === '/api/v1/auth/forgot-password')).toEqual([
        { method: 'POST', path: '/api/v1/auth/forgot-password', body: { email } },
      ]);
    },
  );

  it('drops the previous answer when a malformed resubmit is refused at its field', async () => {
    const { calls, user } = renderPage({
      [FORGOT]: { status: 202, body: { status: 'reset_requested' } },
    });

    await submit(user, 'an@example.com');
    await screen.findByRole('status');
    await user.clear(screen.getByLabelText('Email'));
    await submit(user, 'an.example.com');

    expect(await screen.findByRole('alert')).toHaveTextContent('Enter a valid email address');
    expect(screen.queryByText(/If an account exists for/)).not.toBeInTheDocument();
    expect(calls.filter((call) => call.path === '/api/v1/auth/forgot-password')).toHaveLength(1);
  });

  it('shows the wait in whole minutes when rate limited', async () => {
    const { user } = renderPage({
      [FORGOT]: {
        status: 429,
        body: {
          status: 429,
          detail: 'Too many reset requests. Try again later.',
          retryAfterSeconds: 1250,
        },
      },
    });

    await submit(user, 'an@example.com');

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Too many reset requests. Try again in 21 minutes.',
    );
  });

  it('reads "Sending…" and stays disabled until the answer arrives', async () => {
    let answer: (reply: { status: number; body: unknown }) => void = () => {};
    const { user } = renderPage({
      [FORGOT]: () =>
        new Promise((resolve) => {
          answer = resolve;
        }),
    });

    await submit(user, 'an@example.com');

    expect(screen.getByRole('button', { name: 'Sending…' })).toBeDisabled();
    answer({ status: 202, body: { status: 'reset_requested' } });
    expect(await screen.findByRole('button', { name: 'Send reset link' })).toBeEnabled();
  });

  it.each<[string, Handler]>([
    ['switched off', { status: 200, body: { ...FEATURES, passwordReset: false } }],
    ['unreachable', { status: 500, body: { status: 500 } }],
  ])('says reset is unavailable while the switch is %s', async (_label, features) => {
    renderPage({ 'GET /api/v1/features': features });

    expect(
      await screen.findByText('Password reset is not available right now'),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Back to sign in' })).toHaveAttribute('href', '/login');
    expect(screen.queryByLabelText('Email')).not.toBeInTheDocument();
  });
});
