import { setToken } from '@flow-cortex/api-client';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, settled, stubApi, type FakeReply } from '../../test/fakeApi';
import { PhoneVerification } from './PhoneVerification';
import { ProfilePage } from './ProfilePage';

const PHONE = 'GET /api/v1/me/phone';
const SEND = 'POST /api/v1/me/phone/send-code';
const VERIFY = 'POST /api/v1/me/phone/verify';

const UNVERIFIED: FakeReply = { status: 200, body: { phone: null, verified: false, maskedPhone: null } };
const VERIFIED_BODY = { phone: '+84912345678', verified: true, maskedPhone: '+84 9•• ••• 678' };
const SENT: FakeReply = {
  status: 200,
  body: { maskedPhone: '+84 9•• ••• 678', resendAfterSeconds: 60, expiresAt: '2026-10-16T10:05:00Z' },
};

function problem(status: number, detail: string, extra: Record<string, unknown> = {}): FakeReply {
  return { status, body: { status, detail, ...extra } };
}

function renderSection() {
  return renderRoute('/profile', '/profile', <PhoneVerification />);
}

async function sendNumber(user: ReturnType<typeof userEvent.setup>, number = '0912345678') {
  await user.type(await screen.findByLabelText('Mobile number'), number);
  await user.click(screen.getByRole('button', { name: 'Send code' }));
}

async function enterCode(user: ReturnType<typeof userEvent.setup>, code: string) {
  const input = await screen.findByLabelText('6-digit code');
  await user.clear(input);
  await user.type(input, code);
  await user.click(screen.getByRole('button', { name: 'Verify' }));
}

describe('PhoneVerification', () => {
  beforeEach(() => setToken('t', true));
  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
  });

  it('sends a valid number and asks for the code sent to the masked number', async () => {
    const calls = stubApi({ [PHONE]: UNVERIFIED, [SEND]: SENT });
    const user = userEvent.setup();
    renderSection();

    await sendNumber(user);

    expect(await screen.findByText('We sent a 6-digit code to +84 9•• ••• 678')).toBeInTheDocument();
    expect(screen.getByLabelText('6-digit code')).toBeInTheDocument();
    expect(calls.find((call) => call.method === 'POST')?.body).toEqual({ phone: '0912345678' });
  });

  it('shows the 400 message at the phone field and stays on the number step', async () => {
    stubApi({
      [PHONE]: UNVERIFIED,
      [SEND]: problem(400, 'Validation failed', {
        errors: { phone: 'Enter a valid Vietnamese mobile number' },
      }),
    });
    const user = userEvent.setup();
    renderSection();

    await sendNumber(user, '12345');

    expect(await screen.findByText('Enter a valid Vietnamese mobile number')).toBeInTheDocument();
    expect(screen.getByLabelText('Mobile number')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.queryByLabelText('6-digit code')).not.toBeInTheDocument();
  });

  it('shows the 409 detail when the number is verified on another account', async () => {
    stubApi({
      [PHONE]: UNVERIFIED,
      [SEND]: problem(409, 'This number is already verified on another account'),
    });
    const user = userEvent.setup();
    renderSection();

    await sendNumber(user);

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'This number is already verified on another account',
    );
  });

  it('says how many tries are left after a wrong code', async () => {
    stubApi({
      [PHONE]: UNVERIFIED,
      [SEND]: SENT,
      [VERIFY]: problem(422, 'Wrong code. 1 try left.', { code: 'OTP_INVALID' }),
    });
    const user = userEvent.setup();
    renderSection();
    await sendNumber(user);

    await enterCode(user, '000000');

    expect(await screen.findByRole('alert')).toHaveTextContent('Wrong code. 1 try left.');
    expect(screen.getByLabelText('6-digit code')).toHaveAttribute('aria-invalid', 'true');
  });

  it.each([
    ['OTP_EXPIRED', 'This code has expired. Request a new one.'],
    ['OTP_LOCKED', 'Too many wrong codes. Request a new one.'],
  ])('shows the %s message', async (code, detail) => {
    stubApi({ [PHONE]: UNVERIFIED, [SEND]: SENT, [VERIFY]: problem(422, detail, { code }) });
    const user = userEvent.setup();
    renderSection();
    await sendNumber(user);

    await enterCode(user, '123456');

    expect(await screen.findByRole('alert')).toHaveTextContent(detail);
  });

  it('keeps Resend code disabled and counts down from retryAfterSeconds on OTP_COOLDOWN, then enables it', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    onTestFinished(() => {
      vi.useRealTimers();
    });
    stubApi({
      [PHONE]: UNVERIFIED,
      [SEND]: problem(429, 'Wait 42 seconds before requesting a new code.', {
        code: 'OTP_COOLDOWN',
        retryAfterSeconds: 42,
      }),
    });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderSection();

    await sendNumber(user);

    expect(await screen.findByText('Resend in 0:42')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('Wait 42 seconds before requesting a new code.');
    const resend = screen.getByRole('button', { name: 'Resend code' });
    expect(resend).toBeDisabled();

    await vi.advanceTimersByTimeAsync(1000);
    expect(screen.getByText('Resend in 0:41')).toBeInTheDocument();
    expect(resend).toBeDisabled();

    await vi.advanceTimersByTimeAsync(41_000);
    expect(screen.queryByText(/Resend in/)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Resend code' })).toBeEnabled();
  });

  it('starts the resend countdown from resendAfterSeconds after a send', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    onTestFinished(() => {
      vi.useRealTimers();
    });
    const calls = stubApi({ [PHONE]: UNVERIFIED, [SEND]: SENT });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderSection();

    await sendNumber(user);

    expect(await screen.findByText('Resend in 1:00')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Resend code' })).toBeDisabled();

    await vi.advanceTimersByTimeAsync(60_000);
    await user.click(screen.getByRole('button', { name: 'Resend code' }));
    await waitFor(() => expect(calls.filter((call) => call.method === 'POST')).toHaveLength(2));
    expect(calls.filter((call) => call.method === 'POST')[1]?.body).toEqual({ phone: '0912345678' });
  });

  it.each([
    ['OTP_HOURLY_LIMIT', "You've requested too many codes. Try again in 42 minutes."],
    ['OTP_DAILY_LIMIT', "You've reached today's limit for this number. Try again tomorrow."],
  ])('shows the %s message', async (code, detail) => {
    stubApi({ [PHONE]: UNVERIFIED, [SEND]: problem(429, detail, { code, retryAfterSeconds: 2520 }) });
    const user = userEvent.setup();
    renderSection();

    await sendNumber(user);

    expect(await screen.findByRole('alert')).toHaveTextContent(detail);
  });

  it('shows the number with a Verified badge once the right code is entered', async () => {
    stubApi({ [PHONE]: UNVERIFIED, [SEND]: SENT, [VERIFY]: { status: 200, body: VERIFIED_BODY } });
    const user = userEvent.setup();
    renderSection();
    await sendNumber(user);

    await enterCode(user, '123456');

    expect(await screen.findByRole('status')).toHaveTextContent('Phone number verified');
    expect(screen.getByText('+84912345678')).toBeInTheDocument();
    expect(screen.getByText('Verified')).toBeInTheDocument();
    expect(screen.queryByLabelText('6-digit code')).not.toBeInTheDocument();
  });

  it('shows an already verified number with its badge and offers to change it', async () => {
    stubApi({ [PHONE]: { status: 200, body: VERIFIED_BODY } });
    const user = userEvent.setup();
    renderSection();

    expect(await screen.findByText('+84912345678')).toBeInTheDocument();
    expect(screen.getByText('Verified')).toBeInTheDocument();
    expect(screen.queryByLabelText('Mobile number')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Change number' }));
    expect(screen.getByLabelText('Mobile number')).toBeInTheDocument();
  });

  it('shows an alert with a retry action when the phone status cannot be loaded', async () => {
    const calls = stubApi({ [PHONE]: problem(500, 'boom') });
    const user = userEvent.setup();
    renderSection();

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load your phone number.');
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(calls.filter((call) => call.path === '/api/v1/me/phone')).toHaveLength(2));
  });

  describe('on the profile page', () => {
    const ME = { id: 'u1', email: 'member1@flowcortex.dev', name: 'Member One', role: 'MEMBER', status: 'ACTIVE' };
    const FEATURES = {
      emailConfirmation: false,
      phoneVerification: false,
      uploadContentCheck: false,
      runCancellation: false,
      credits: false,
    };

    it('shows the phone section when phone verification is on', async () => {
      stubApi({
        'GET /api/v1/auth/me': { status: 200, body: ME },
        'GET /api/v1/features': { status: 200, body: { ...FEATURES, phoneVerification: true } },
        [PHONE]: UNVERIFIED,
      });
      renderRoute('/profile', '/profile', <ProfilePage />);

      expect(await screen.findByRole('region', { name: 'Phone number' })).toBeInTheDocument();
    });

    it('leaves the phone section out when phone verification is off', async () => {
      const calls = stubApi({
        'GET /api/v1/auth/me': { status: 200, body: ME },
        'GET /api/v1/features': { status: 200, body: FEATURES },
      });
      const { queryClient } = renderRoute('/profile', '/profile', <ProfilePage />);

      await screen.findByText('Member One');
      await settled(queryClient);
      expect(screen.queryByRole('region', { name: 'Phone number' })).not.toBeInTheDocument();
      expect(calls.some((call) => call.path === '/api/v1/me/phone')).toBe(false);
    });
  });
});
