import { setToken } from '@flow-cortex/api-client';
import { act, screen, waitFor } from '@testing-library/react';
import { renderRoute, settled, stubApi, type FakeReply } from '../test/fakeApi';
import { Header } from './Header';

const FEATURES = {
  emailConfirmation: false,
  phoneVerification: false,
  uploadContentCheck: false,
  runCancellation: false,
  credits: true,
};

const ME = {
  status: 200,
  body: { id: 'u1', email: 'member1@flowcortex.dev', name: 'Member One', role: 'MEMBER' },
};

function stubWithMe(routes: Parameters<typeof stubApi>[0]) {
  return stubApi({ 'GET /api/v1/auth/me': ME, ...routes });
}

function renderHeader() {
  return renderRoute('/', '/', <Header />);
}

function balanceCalls(calls: { path: string }[]) {
  return calls.filter((call) => call.path === '/api/v1/credits/balance');
}

describe('Header credits', () => {
  beforeEach(() => setToken('t', true));
  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
  });

  it('shows the balance from the credits endpoint, grouped the en-US way', async () => {
    stubWithMe({
      'GET /api/v1/features': { status: 200, body: FEATURES },
      'GET /api/v1/credits/balance': { status: 200, body: { balance: 1000 } },
    });

    renderHeader();

    expect(await screen.findByText('1,000 credits')).toBeInTheDocument();
    expect(screen.queryByText(/GPU Credits/)).not.toBeInTheDocument();
  });

  it('shows no credits and never asks for the balance when credits are off', async () => {
    vi.stubEnv('VITE_ACCOUNT_STATS_ENABLED', 'true');
    const calls = stubWithMe({
      'GET /api/v1/features': { status: 200, body: { ...FEATURES, credits: false } },
      'GET /api/v1/credits/balance': { status: 200, body: { balance: 1000 } },
    });

    const { queryClient } = renderHeader();
    await waitFor(() => expect(calls.some((call) => call.path === '/api/v1/features')).toBe(true));
    await settled(queryClient);

    expect(screen.queryByText(/credit/i)).not.toBeInTheDocument();
    expect(balanceCalls(calls)).toHaveLength(0);
  });

  it('shows no credits when the balance request fails', async () => {
    const calls = stubWithMe({
      'GET /api/v1/features': { status: 200, body: FEATURES },
      'GET /api/v1/credits/balance': {
        status: 503,
        body: { status: 503, detail: 'Credits are switched off' },
      },
    });

    const { queryClient } = renderHeader();
    await waitFor(() => expect(balanceCalls(calls)).toHaveLength(1));
    await settled(queryClient);

    expect(screen.queryByText(/credit/i)).not.toBeInTheDocument();
  });

  it('reloads the balance once a run on screen reaches a terminal status', async () => {
    let reply: FakeReply = { status: 200, body: { balance: 1000 } };
    const calls = stubWithMe({
      'GET /api/v1/features': { status: 200, body: FEATURES },
      'GET /api/v1/credits/balance': () => reply,
    });

    const { queryClient } = renderHeader();
    expect(await screen.findByText('1,000 credits')).toBeInTheDocument();

    act(() => queryClient.setQueryData(['runs', 'r1'], { id: 'r1', status: 'running' }));
    await settled(queryClient);
    expect(balanceCalls(calls)).toHaveLength(1);

    reply = { status: 200, body: { balance: 940 } };
    act(() => queryClient.setQueryData(['runs', 'r1'], { id: 'r1', status: 'completed' }));

    expect(await screen.findByText('940 credits')).toBeInTheDocument();
    expect(balanceCalls(calls)).toHaveLength(2);
  });
});
