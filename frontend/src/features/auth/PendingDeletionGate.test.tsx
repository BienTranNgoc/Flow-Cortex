import { QueryClientProvider, useQuery } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { authHeaders, listWorkflows, setToken, unwrap } from '@flow-cortex/api-client';
import { App } from '../../App';
import { queryClient as appQueryClient } from '../../app/queryClient';
import { renderRoute, stubApi, type FakeReply } from '../../test/fakeApi';
import { PendingDeletionGate } from './PendingDeletionGate';
import { PendingDeletionPage } from './PendingDeletionPage';

const ME_PATH = 'GET /api/v1/auth/me';
const CANCEL = 'POST /api/v1/me/account-deletion/cancel';
const HOUR = 3_600_000;
const DAY = 24 * HOUR;

const MEMBER = {
  id: 'u-1',
  email: 'an@example.com',
  name: 'An',
  role: 'MEMBER',
  status: 'ACTIVE',
  hasPassword: true,
  passwordSetupRequired: false,
  phoneVerificationRequired: false,
};

function iso(offsetMs: number): string {
  return new Date(Date.now() + offsetMs).toISOString();
}

function meWith(state: 'PENDING' | 'EXPIRED' | null, scheduledOffsetMs = 12 * DAY + 3 * HOUR): FakeReply {
  return {
    status: 200,
    body:
      state === null
        ? MEMBER
        : {
            ...MEMBER,
            accountDeletion: { state, requestedAt: iso(-DAY), scheduledAt: iso(scheduledOffsetMs) },
          },
  };
}

function renderGated(url = '/workflows') {
  return renderRoute(
    '/workflows',
    url,
    <PendingDeletionGate>
      <p>Workspace</p>
    </PendingDeletionGate>,
    <>
      <Route path="/account-deletion" element={<PendingDeletionPage />} />
      <Route path="/" element={<p>Home</p>} />
      <Route path="/login" element={<p>Sign in page</p>} />
    </>,
  );
}

describe('PendingDeletionGate', () => {
  beforeEach(() => setToken('jwt', false));

  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
    appQueryClient.clear();
    window.history.pushState({}, '', '/');
  });

  it('sends a pending member to /account-deletion from a deep link', async () => {
    stubApi({ [ME_PATH]: meWith('PENDING') });

    renderGated();

    const heading = await screen.findByRole('heading', { name: 'Your account is scheduled for deletion' });
    expect(screen.queryByText('Workspace')).not.toBeInTheDocument();
    expect(screen.getByText('13 days left')).toBeInTheDocument();
    expect(screen.getByText(/You can cancel until /)).toBeInTheDocument();
    expect(screen.getByText(/Every device, including this one, is signed out within minutes/)).toBeInTheDocument();
    await waitFor(() => expect(heading).toHaveFocus());
  });

  it('lets a member with no deletion request through', async () => {
    stubApi({ [ME_PATH]: meWith(null) });

    renderGated();

    expect(await screen.findByText('Workspace')).toBeInTheDocument();
  });

  it('cancels from the screen and lands on the home page', async () => {
    let cancelled = false;
    stubApi({
      [ME_PATH]: () => meWith(cancelled ? null : 'PENDING'),
      [CANCEL]: () => {
        cancelled = true;
        return { status: 200, body: { state: 'CANCELLED', cancelledAt: iso(0) } };
      },
    });
    const user = userEvent.setup();

    renderGated();

    await user.click(await screen.findByRole('button', { name: 'Cancel deletion' }));

    expect(await screen.findByText('Home')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Your account is scheduled for deletion' })).not.toBeInTheDocument();
  });

  it('shows the refusal and stays when the cancel is refused after the deadline', async () => {
    let expired = false;
    stubApi({
      [ME_PATH]: () => (expired ? meWith('EXPIRED', -HOUR) : meWith('PENDING', HOUR)),
      [CANCEL]: () => {
        expired = true;
        return {
          status: 422,
          body: {
            status: 422,
            code: 'account.deletion.recoveryExpired',
            detail: 'The deadline has passed, so the deletion can no longer be cancelled.',
          },
        };
      },
    });
    const user = userEvent.setup();

    renderGated();

    await user.click(await screen.findByRole('button', { name: 'Cancel deletion' }));

    expect(await screen.findByRole('heading', { name: 'The deletion deadline has passed' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancel deletion' })).not.toBeInTheDocument();
  });

  it('keeps the screen and offers a retry when the cancel fails', async () => {
    stubApi({
      [ME_PATH]: meWith('PENDING'),
      [CANCEL]: { status: 500, body: { status: 500, detail: 'boom' } },
    });
    const user = userEvent.setup();

    renderGated();

    await user.click(await screen.findByRole('button', { name: 'Cancel deletion' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('We could not cancel the deletion. Try again.');
    expect(screen.getByRole('button', { name: 'Cancel deletion' })).toBeEnabled();
  });

  it('sends a member who has no request away from /account-deletion to /', async () => {
    stubApi({ [ME_PATH]: meWith(null) });

    renderRoute('/account-deletion', '/account-deletion', <PendingDeletionPage />, <Route path="/" element={<p>Home</p>} />);

    expect(await screen.findByText('Home')).toBeInTheDocument();
  });

  it('refetches /me and shows /account-deletion after a 403 ACCOUNT_DELETION_PENDING', async () => {
    let pending = false;
    const calls = stubApi({
      [ME_PATH]: () => meWith(pending ? 'PENDING' : null),
      'GET /api/v1/workflows': () => {
        pending = true;
        return {
          status: 403,
          body: {
            status: 403,
            code: 'ACCOUNT_DELETION_PENDING',
            detail: 'Your account is scheduled for deletion.',
          },
        };
      },
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
                <PendingDeletionGate>
                  <Workflows />
                </PendingDeletionGate>
              }
            />
            <Route path="/account-deletion" element={<PendingDeletionPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(await screen.findByRole('heading', { name: 'Your account is scheduled for deletion' })).toBeInTheDocument();
    expect(calls.filter((call) => call.path === '/api/v1/auth/me').length).toBeGreaterThan(1);
  });

  it('offers no cancel when the state is EXPIRED', async () => {
    stubApi({ [ME_PATH]: meWith('EXPIRED', -2 * HOUR) });

    renderGated();

    expect(await screen.findByRole('heading', { name: 'The deletion deadline has passed' })).toBeInTheDocument();
    expect(screen.getByText(/can no longer be cancelled/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancel deletion' })).not.toBeInTheDocument();
    expect(screen.queryByText(/days? left/)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeInTheDocument();
  });
});

describe('the Help Center widget beside the deletion wall', () => {
  const FEATURES: FakeReply = {
    status: 200,
    body: {
      emailConfirmation: false,
      phoneVerification: false,
      uploadContentCheck: false,
      runCancellation: false,
      credits: false,
      supportWidget: true,
    },
  };

  // The widget loads lazily; with its chunk in memory it mounts in the same tick as the features.
  beforeAll(async () => {
    await import('../support/SupportWidget');
  });

  beforeEach(() => {
    setToken('jwt', false);
    window.history.pushState({}, '', '/workflows');
  });

  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
    appQueryClient.clear();
    window.history.pushState({}, '', '/');
  });

  it('renders no widget and calls no support route while pending', async () => {
    const calls = stubApi({
      [ME_PATH]: meWith('PENDING'),
      'GET /api/v1/features': FEATURES,
    });

    render(<App />);

    await screen.findByRole('heading', { name: 'Your account is scheduled for deletion' });
    await waitFor(() => expect(calls.some((call) => call.path === '/api/v1/features')).toBe(true));
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(screen.queryByRole('button', { name: 'Help' })).not.toBeInTheDocument();
    expect(calls.filter((call) => call.path.startsWith('/api/v1/support/'))).toEqual([]);
  });
});
