import { getToken, setToken } from '@flow-cortex/api-client';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, useLocation } from 'react-router-dom';
import i18n from '../../lib/i18n';
import { renderRoute, stubApi } from '../../test/fakeApi';
import { TotpSignInPage } from './TotpSignInPage';

const COMPLETE = 'POST /api/v1/auth/login/totp';
const PERSISTENT_KEY = 'flowcortex.auth.token';
const SESSION_KEY = 'flowcortex.auth.sessionToken';

function SetPasswordStep() {
  const state = useLocation().state as { passwordSetupToken?: string | null } | null;
  return <p>Set password step with {state?.passwordSetupToken ?? 'no token'}</p>;
}

function renderCodeStep(url: Parameters<typeof renderRoute>[1]) {
  return renderRoute(
    '/login/totp',
    url,
    <TotpSignInPage />,
    <>
      <Route path="/" element={<p>Home</p>} />
      <Route path="/profile" element={<p>Profile landing</p>} />
      <Route path="/login" element={<p>Login page</p>} />
      <Route path="/set-password" element={<SetPasswordStep />} />
    </>,
  );
}

const fromProfile = { pathname: '/login/totp', state: { from: '/profile' } };
const session = {
  status: 200,
  body: { accessToken: 'jwt-totp', expiresIn: 900, persistent: true },
};

describe('TotpSignInPage', () => {
  afterEach(() => {
    setToken(null);
    window.localStorage.clear();
    window.sessionStorage.clear();
    vi.unstubAllGlobals();
    void i18n.changeLanguage('en');
  });

  it('sends the typed code, stores the token where the answer says and continues to the return path', async () => {
    const calls = stubApi({ [COMPLETE]: session });
    const user = userEvent.setup();
    renderCodeStep(fromProfile);

    await user.type(screen.getByLabelText('Authentication code'), '123 456');
    await user.click(screen.getByRole('button', { name: /Verify and sign in/ }));

    expect(await screen.findByText('Profile landing')).toBeInTheDocument();
    expect(calls).toEqual([
      { method: 'POST', path: '/api/v1/auth/login/totp', body: { code: '123 456' } },
    ]);
    expect(getToken()).toBe('jwt-totp');
    expect(window.localStorage.getItem(PERSISTENT_KEY)).toBe('jwt-totp');
  });

  it('keeps the token for this browser session only when the sign-in was not remembered', async () => {
    stubApi({
      [COMPLETE]: {
        status: 200,
        body: { accessToken: 'jwt-s', expiresIn: 900, persistent: false },
      },
    });
    const user = userEvent.setup();
    renderCodeStep(fromProfile);

    await user.type(screen.getByLabelText('Authentication code'), '123456');
    await user.click(screen.getByRole('button', { name: /Verify and sign in/ }));

    expect(await screen.findByText('Profile landing')).toBeInTheDocument();
    expect(window.sessionStorage.getItem(SESSION_KEY)).toBe('jwt-s');
    expect(window.localStorage.getItem(PERSISTENT_KEY)).toBeNull();
  });

  it('takes a recovery code as typed after switching the field', async () => {
    const calls = stubApi({ [COMPLETE]: session });
    const user = userEvent.setup();
    renderCodeStep(fromProfile);

    await user.click(screen.getByRole('button', { name: 'Use a recovery code' }));
    await user.type(screen.getByLabelText('Recovery code'), 'abcde-23456');
    await user.click(screen.getByRole('button', { name: /Verify and sign in/ }));

    expect(await screen.findByText('Profile landing')).toBeInTheDocument();
    expect(calls[0]?.body).toEqual({ code: 'abcde-23456' });
  });

  it('labels the field for a one-time code and switches back from a recovery code', async () => {
    renderCodeStep(fromProfile);
    const user = userEvent.setup();

    const field = screen.getByLabelText('Authentication code');
    expect(field).toHaveAttribute('autocomplete', 'one-time-code');
    expect(field).toHaveAttribute('inputmode', 'numeric');

    await user.click(screen.getByRole('button', { name: 'Use a recovery code' }));
    expect(screen.getByLabelText('Recovery code')).toHaveAttribute('autocomplete', 'off');

    await user.click(screen.getByRole('button', { name: 'Use an authentication code instead' }));
    expect(screen.getByLabelText('Authentication code')).toBeInTheDocument();
  });

  it('refuses a code of the wrong shape without sending anything', async () => {
    const calls = stubApi({ [COMPLETE]: session });
    const user = userEvent.setup();
    renderCodeStep(fromProfile);

    await user.type(screen.getByLabelText('Authentication code'), '12345');
    await user.click(screen.getByRole('button', { name: /Verify and sign in/ }));

    const field = screen.getByLabelText('Authentication code');
    expect(await screen.findByText('Enter the 6-digit code from your app.')).toHaveAttribute(
      'role',
      'alert',
    );
    expect(field).toHaveAttribute('aria-invalid', 'true');
    expect(calls).toEqual([]);
  });

  it('says a wrong code is wrong, empties the field and lets the member try again', async () => {
    stubApi({
      [COMPLETE]: {
        status: 422,
        body: { status: 422, code: 'TOTP_INVALID_CODE', detail: 'The code is not valid.' },
      },
    });
    const user = userEvent.setup();
    renderCodeStep(fromProfile);

    await user.type(screen.getByLabelText('Authentication code'), '123456');
    await user.click(screen.getByRole('button', { name: /Verify and sign in/ }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'That code is not correct. Check your app and try again.',
    );
    const field = screen.getByLabelText('Authentication code');
    expect(field).toHaveValue('');
    expect(field).toHaveFocus();
    expect(screen.getByRole('button', { name: /Verify and sign in/ })).toBeEnabled();
    expect(getToken()).toBeNull();

    await user.type(field, '1');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('shows when the lock ends and keeps submit disabled until then', async () => {
    const lockedUntil = new Date(Date.now() + 10 * 60_000).toISOString();
    stubApi({
      [COMPLETE]: {
        status: 423,
        body: { status: 423, code: 'TOTP_LOCKED', detail: 'Locked.', lockedUntil },
      },
    });
    const user = userEvent.setup();
    renderCodeStep(fromProfile);

    await user.type(screen.getByLabelText('Authentication code'), '123456');
    await user.click(screen.getByRole('button', { name: /Verify and sign in/ }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      /^Too many wrong codes\. Try again at .+\.$/,
    );
    expect(screen.getByRole('button', { name: /Verify and sign in/ })).toBeDisabled();
    expect(getToken()).toBeNull();
  });

  it('says the step expired and links back to sign-in when the challenge is gone', async () => {
    stubApi({
      [COMPLETE]: {
        status: 401,
        body: { status: 401, code: 'TOTP_CHALLENGE_INVALID', detail: 'Expired.' },
      },
    });
    const user = userEvent.setup();
    renderCodeStep(fromProfile);

    await user.type(screen.getByLabelText('Authentication code'), '123456');
    await user.click(screen.getByRole('button', { name: /Verify and sign in/ }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('This sign-in step has expired. Sign in again.');
    expect(screen.queryByLabelText('Authentication code')).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Back to sign in' })).toHaveAttribute('href', '/login');
    expect(within(alert).queryByRole('link')).not.toBeInTheDocument();
  });

  it('tells a member whose secret cannot be read to ask an administrator', async () => {
    stubApi({
      [COMPLETE]: {
        status: 503,
        body: { status: 503, code: 'TOTP_UNAVAILABLE', detail: 'Unavailable.' },
      },
    });
    const user = userEvent.setup();
    renderCodeStep(fromProfile);

    await user.type(screen.getByLabelText('Authentication code'), '123456');
    await user.click(screen.getByRole('button', { name: /Verify and sign in/ }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'We cannot check codes for this account right now. Ask an administrator to reset two-factor authentication.',
    );
  });

  it('continues to the return path of a Google sign-in from the address bar', async () => {
    stubApi({ [COMPLETE]: session });
    const user = userEvent.setup();
    renderCodeStep({ pathname: '/login/totp', search: '?return_to=%2Fprofile' });

    await user.type(screen.getByLabelText('Authentication code'), '123456');
    await user.click(screen.getByRole('button', { name: /Verify and sign in/ }));

    expect(await screen.findByText('Profile landing')).toBeInTheDocument();
  });

  it.each(['https://evil.example/steal', '//evil.example/steal', '/\\evil.example'])(
    'goes home instead of leaving the site for the return target %s',
    async (target) => {
      stubApi({ [COMPLETE]: session });
      const user = userEvent.setup();
      renderCodeStep({
        pathname: '/login/totp',
        search: `?return_to=${encodeURIComponent(target)}`,
      });

      await user.type(screen.getByLabelText('Authentication code'), '123456');
      await user.click(screen.getByRole('button', { name: /Verify and sign in/ }));

      expect(await screen.findByText('Home')).toBeInTheDocument();
    },
  );

  it('goes on to the password form with the setup token an emailed link handed over', async () => {
    stubApi({ [COMPLETE]: session });
    const user = userEvent.setup();
    renderCodeStep({ pathname: '/login/totp', state: { passwordSetup: { token: 'setup-9' } } });

    await user.type(screen.getByLabelText('Authentication code'), '123456');
    await user.click(screen.getByRole('button', { name: /Verify and sign in/ }));

    expect(await screen.findByText('Set password step with setup-9')).toBeInTheDocument();
  });

  it('is written in Vietnamese when the language is Vietnamese', async () => {
    await i18n.changeLanguage('vi');
    renderCodeStep(fromProfile);

    expect(screen.getByRole('heading', { level: 1, name: 'Nhập mã xác thực' })).toBeInTheDocument();
    expect(screen.getByLabelText('Mã xác thực')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Dùng mã khôi phục' })).toBeInTheDocument();
  });
});
