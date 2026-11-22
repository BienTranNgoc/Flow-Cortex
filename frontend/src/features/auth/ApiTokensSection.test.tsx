import { setToken } from '@flow-cortex/api-client';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, stubApi, type FakeReply } from '../../test/fakeApi';
import { stubModalDialog } from '../../test/modalDialog';
import { ApiTokensSection } from './ApiTokensSection';

const LIST = 'GET /api/v1/me/api-tokens';
const CREATE = 'POST /api/v1/me/api-tokens';
const STEP_UP = 'POST /api/v1/me/step-up';

const RAW_TOKEN = 'fc_pat_one-time-secret-value';

const NIGHTLY = {
  id: 't1',
  name: 'nightly',
  createdAt: '2026-11-01T08:00:00Z',
  expiresAt: '2026-12-01T08:00:00Z',
  expired: false,
};

function renderSection() {
  return renderRoute('/profile', '/profile', <ApiTokensSection />);
}

describe('ApiTokensSection', () => {
  let restoreDialog: () => void;
  beforeEach(() => {
    restoreDialog = stubModalDialog();
    setToken('t', true);
  });
  afterEach(() => {
    restoreDialog();
    setToken(null);
    vi.unstubAllGlobals();
  });

  it('shows a loading note, then the empty state when the member holds no tokens', async () => {
    let release: (reply: FakeReply) => void = () => {};
    stubApi({
      [LIST]: () =>
        new Promise<FakeReply>((resolve) => {
          release = resolve;
        }),
    });

    renderSection();

    expect(await screen.findByText('Loading your tokens…')).toBeInTheDocument();
    release({ status: 200, body: [] });
    expect(await screen.findByText('You have no API tokens.')).toBeInTheDocument();
    expect(screen.queryByText('Loading your tokens…')).not.toBeInTheDocument();
  });

  it('says the list could not load and offers a retry that reloads it', async () => {
    let reply: FakeReply = { status: 500, body: { status: 500, detail: 'boom' } };
    const calls = stubApi({ [LIST]: () => reply });
    const user = userEvent.setup();

    renderSection();

    expect(await screen.findByText(/Could not load your tokens\./)).toBeInTheDocument();
    reply = { status: 200, body: [NIGHTLY] };
    await user.click(screen.getByRole('button', { name: 'Retry' }));

    expect(await screen.findByText('nightly')).toBeInTheDocument();
    expect(calls.filter((call) => call.method === 'GET')).toHaveLength(2);
  });

  it('reveals the new token once and keeps it neither on screen nor in the mutation cache afterwards', async () => {
    const calls = stubApi({
      [LIST]: { status: 200, body: [] },
      [STEP_UP]: {
        status: 200,
        body: { stepUpProof: 'proof-1', expiresAt: '2026-11-11T10:05:00Z' },
      },
      [CREATE]: {
        status: 201,
        body: {
          id: 't2',
          name: 'nightly',
          token: RAW_TOKEN,
          createdAt: '2026-11-11T10:00:00Z',
          expiresAt: '2027-02-09T10:00:00Z',
        },
      },
    });
    const user = userEvent.setup();
    const { queryClient } = renderSection();

    await user.type(await screen.findByLabelText('Token name'), 'nightly');
    await user.click(screen.getByRole('button', { name: 'Create token' }));
    await user.type(await screen.findByLabelText('Password'), 'correct horse');
    await user.click(screen.getByRole('button', { name: 'Confirm' }));

    const revealed = await screen.findByLabelText('Token');
    expect(revealed).toHaveValue(RAW_TOKEN);
    expect(screen.getByText('Token “nightly” created')).toBeInTheDocument();
    expect(
      calls.find((call) => call.method === 'POST' && call.path.endsWith('/api-tokens'))?.body,
    ).toEqual({
      name: 'nightly',
      expiresInDays: 90,
      stepUpProof: 'proof-1',
    });
    await waitFor(() =>
      expect(
        JSON.stringify(
          queryClient
            .getMutationCache()
            .getAll()
            .map((m) => m.state.data),
        ),
      ).not.toContain(RAW_TOKEN),
    );

    await user.click(screen.getByRole('button', { name: 'I have copied it' }));

    expect(screen.queryByDisplayValue(RAW_TOKEN)).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Token')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Token name')).toHaveValue('');
  });

  it('asks for confirmation before revoking, lets the member keep the token, then revokes it', async () => {
    let tokens: unknown[] = [NIGHTLY];
    const calls = stubApi({
      [LIST]: () => ({ status: 200, body: tokens }),
      'DELETE /api/v1/me/api-tokens/t1': () => {
        tokens = [];
        return { status: 204 };
      },
    });
    const user = userEvent.setup();
    renderSection();

    await user.click(await screen.findByRole('button', { name: 'Revoke token nightly' }));
    expect(
      screen.getByRole('group', {
        name: 'Revoke the token “nightly”? Scripts that use it stop working.',
      }),
    ).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Keep token' }));
    expect(screen.getByRole('button', { name: 'Revoke token nightly' })).toBeInTheDocument();
    expect(calls.some((call) => call.method === 'DELETE')).toBe(false);

    await user.click(screen.getByRole('button', { name: 'Revoke token nightly' }));
    await user.click(screen.getByRole('button', { name: 'Revoke token' }));

    expect(await screen.findByText('You have no API tokens.')).toBeInTheDocument();
    await waitFor(() =>
      expect(calls).toContainEqual(
        expect.objectContaining({ method: 'DELETE', path: '/api/v1/me/api-tokens/t1' }),
      ),
    );
    expect(screen.queryByText('nightly')).not.toBeInTheDocument();
  });
});
