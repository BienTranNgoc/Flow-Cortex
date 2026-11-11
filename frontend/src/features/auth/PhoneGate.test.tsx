import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { authHeaders, listWorkflows, setToken, unwrap } from '@flow-cortex/api-client';
import { useQuery } from '@tanstack/react-query';
import { queryClient as appQueryClient } from '../../app/queryClient';
import { renderRoute, stubApi, type FakeReply } from '../../test/fakeApi';
import { PhoneGate } from './PhoneGate';
import { VerifyPhonePage } from './VerifyPhonePage';

const ME = {
  id: 'u-1',
  email: 'an@example.com',
  name: 'An',
  role: 'MEMBER',
  status: 'ACTIVE',
  hasPassword: true,
  passwordSetupRequired: false,
};
const PHONE = 'GET /api/v1/me/phone';
const SEND = 'POST /api/v1/me/phone/send-code';
const VERIFY = 'POST /api/v1/me/phone/verify';
const ME_PATH = 'GET /api/v1/auth/me';

const UNVERIFIED: FakeReply = { status: 200, body: { phone: null, verified: false, maskedPhone: null } };
const VERIFIED: FakeReply = {
  status: 200,
  body: { phone: '+84912345678', verified: true, maskedPhone: '+84 9•• ••• 678' },
};
const SENT: FakeReply = {
  status: 200,
  body: { maskedPhone: '+84 9•• ••• 678', resendAfterSeconds: 60, expiresAt: '2026-10-16T10:05:00Z' },
};

function me(phoneVerificationRequired: boolean): FakeReply {
  return { status: 200, body: { ...ME, phoneVerificationRequired } };
}

function renderGated() {
  return renderRoute(
    '/workflows',
    '/workflows',
    <PhoneGate>
      <p>Workspace</p>
    </PhoneGate>,
    <>
      <Route path="/verify-phone" element={<VerifyPhonePage />} />
      <Route path="/" element={<p>Home</p>} />
      <Route path="/login" element={<p>Sign in page</p>} />
    </>,
  );
}

describe('PhoneGate', () => {
  beforeEach(() => setToken('jwt', false));

  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
    appQueryClient.clear();
  });

  it('sends a held member to /verify-phone and back to the page they asked for once verified', async () => {
    let verified = false;
    stubApi({
      [ME_PATH]: () => me(!verified),
      [PHONE]: () => (verified ? VERIFIED : UNVERIFIED),
      [SEND]: SENT,
      [VERIFY]: () => {
        verified = true;
        return VERIFIED;
      },
    });
    const user = userEvent.setup();

    renderGated();

    expect(await screen.findByRole('heading', { name: 'Verify your phone number' })).toBeInTheDocument();
    expect(screen.queryByText('Workspace')).not.toBeInTheDocument();
    await user.type(await screen.findByLabelText('Mobile number'), '0912345678');
    await user.click(screen.getByRole('button', { name: 'Send code' }));
    await user.type(await screen.findByLabelText('6-digit code'), '123456');
    await user.click(screen.getByRole('button', { name: 'Verify' }));

    expect(await screen.findByText('Workspace')).toBeInTheDocument();
    expect(screen.queryByLabelText('6-digit code')).not.toBeInTheDocument();
  });

  it('renders the shell when phoneVerificationRequired is false', async () => {
    stubApi({ [ME_PATH]: me(false) });

    renderGated();

    expect(await screen.findByText('Workspace')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Verify your phone number' })).not.toBeInTheDocument();
  });

  it('refetches /me and shows /verify-phone after a 403 PHONE_VERIFICATION_REQUIRED', async () => {
    let gated = false;
    const calls = stubApi({
      [ME_PATH]: () => me(gated),
      'GET /api/v1/workflows': () => {
        gated = true;
        return {
          status: 403,
          body: {
            status: 403,
            code: 'PHONE_VERIFICATION_REQUIRED',
            detail: 'Verify your phone number to continue.',
          },
        };
      },
      [PHONE]: UNVERIFIED,
    });

    function Workflows() {
      useQuery({
        queryKey: ['workflows'],
        queryFn: () => unwrap(listWorkflows(authHeaders())),
      });
      return <p>Workspace</p>;
    }

    render(
      <QueryClientProvider client={appQueryClient}>
        <MemoryRouter initialEntries={['/workflows']}>
          <Routes>
            <Route
              path="/workflows"
              element={
                <PhoneGate>
                  <Workflows />
                </PhoneGate>
              }
            />
            <Route path="/verify-phone" element={<VerifyPhonePage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(await screen.findByRole('heading', { name: 'Verify your phone number' })).toBeInTheDocument();
    expect(calls.filter((call) => call.path === '/api/v1/auth/me').length).toBeGreaterThan(1);
  });

  it('sends a member who is not held away from /verify-phone to /', async () => {
    stubApi({ [ME_PATH]: me(false), [PHONE]: UNVERIFIED });

    renderRoute('/verify-phone', '/verify-phone', <VerifyPhonePage />, <Route path="/" element={<p>Home</p>} />);

    expect(await screen.findByText('Home')).toBeInTheDocument();
  });

  it('signs a held member out from the verify-phone page and returns to sign in', async () => {
    const calls = stubApi({
      [ME_PATH]: me(true),
      [PHONE]: UNVERIFIED,
      'POST /api/v1/auth/logout': { status: 204 },
    });
    const user = userEvent.setup();

    renderGated();

    await user.click(await screen.findByRole('button', { name: 'Sign out' }));

    expect(await screen.findByText('Sign in page')).toBeInTheDocument();
    expect(calls.some((call) => call.method === 'POST' && call.path === '/api/v1/auth/logout')).toBe(true);
  });
});
