import { setToken } from '@flow-cortex/api-client';
import { act, screen, waitFor } from '@testing-library/react';
import { renderRoute, settled, stubApi, type FakeReply } from '../test/fakeApi';
import { Topbar } from './Topbar';

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

function renderTopbar() {
  return renderRoute(
    '/',
    '/',
    <Topbar showMenuButton={false} drawerOpen={false} onOpenMenu={() => {}} />,
  );
}

function balanceCalls(calls: { path: string }[]) {
  return calls.filter((call) => call.path === '/api/v1/credits/balance');
}

describe('Topbar credits', () => {
  beforeEach(() => setToken('t', true));
  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
  });

  it('shows no credits and never asks for the balance when credits are off', async () => {
    vi.stubEnv('VITE_ACCOUNT_STATS_ENABLED', 'true');
    const calls = stubWithMe({
      'GET /api/v1/features': { status: 200, body: { ...FEATURES, credits: false } },
      'GET /api/v1/credits/balance': { status: 200, body: { balance: 1000, granted: 1000 } },
    });

    const { queryClient } = renderTopbar();
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

    const { queryClient } = renderTopbar();
    await waitFor(() => expect(balanceCalls(calls)).toHaveLength(1));
    await settled(queryClient);

    expect(screen.queryByText(/credit/i)).not.toBeInTheDocument();
  });

  it('keeps the last balance when a later poll fails', async () => {
    let reply: FakeReply = { status: 200, body: { balance: 1000, granted: 1000 } };
    const calls = stubWithMe({
      'GET /api/v1/features': { status: 200, body: FEATURES },
      'GET /api/v1/credits/balance': () => reply,
    });

    const { queryClient } = renderTopbar();
    const badge = await screen.findByRole('link', { name: /^1,000 credits, balance healthy/ });
    expect(badge).toHaveAttribute('href', '/profile');
    expect(badge).toHaveAttribute('title', '0 of 1,000 credits used');

    reply = { status: 500, body: { status: 500, detail: 'boom' } };
    await act(() => queryClient.invalidateQueries({ queryKey: ['credits', 'balance'] }));
    await waitFor(() => expect(balanceCalls(calls)).toHaveLength(2));
    await settled(queryClient);

    expect(screen.getByText('1,000 credits')).toBeInTheDocument();
  });

  it('reloads the balance once a run on screen reaches a terminal status', async () => {
    let reply: FakeReply = { status: 200, body: { balance: 1000, granted: 1000 } };
    const calls = stubWithMe({
      'GET /api/v1/features': { status: 200, body: FEATURES },
      'GET /api/v1/credits/balance': () => reply,
    });

    const { queryClient } = renderTopbar();
    expect(await screen.findByText('1,000 credits')).toBeInTheDocument();

    act(() => queryClient.setQueryData(['runs', 'r1'], { id: 'r1', status: 'running' }));
    await settled(queryClient);
    expect(balanceCalls(calls)).toHaveLength(1);

    reply = { status: 200, body: { balance: 150, granted: 1000 } };
    act(() => queryClient.setQueryData(['runs', 'r1'], { id: 'r1', status: 'completed' }));

    expect(await screen.findByText('150 credits')).toBeInTheDocument();
    expect(balanceCalls(calls)).toHaveLength(2);
    expect(screen.getByRole('link', { name: /150 credits, balance low/ })).toHaveAttribute(
      'title',
      '850 of 1,000 credits used',
    );
  });
});
