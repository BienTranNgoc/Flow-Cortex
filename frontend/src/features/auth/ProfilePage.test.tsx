import { setToken } from '@flow-cortex/api-client';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route } from 'react-router-dom';
import i18n from '../../lib/i18n';
import viAuth from '../../locales/vi/auth.json';
import { renderRoute, settled, stubApi, type FakeReply } from '../../test/fakeApi';
import { stubModalDialog } from '../../test/modalDialog';
import { ProfilePage } from './ProfilePage';

const ME = {
  id: 'u1',
  email: 'member1@flowcortex.dev',
  name: 'Member One',
  role: 'MEMBER',
  status: 'ACTIVE',
};

const OWN = {
  name: 'Member One',
  firstName: 'Mem',
  lastName: 'Ber',
  username: 'member_one',
};

const PREFERENCES = {
  ui: {},
  privacy: { profile: 'everyone', activity: 'everyone', message: 'everyone', stats: 'show' },
};

const OWN_ROUTES = {
  'GET /api/v1/auth/me': { status: 200, body: ME },
  'GET /api/v1/auth/profile': { status: 200, body: OWN },
  'GET /api/v1/auth/profile/preferences': { status: 200, body: PREFERENCES },
};

function renderProfile() {
  return renderRoute('/profile', '/profile', <ProfilePage />);
}

describe('ProfilePage', () => {
  beforeEach(() => setToken('t', true));
  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
  });

  it('shows the account status from /me and hides placeholder stats by default', async () => {
    stubApi({ 'GET /api/v1/auth/me': { status: 200, body: { ...ME, status: 'SUSPENDED' } } });

    renderProfile();

    expect(await screen.findByText('Suspended')).toBeInTheDocument();
    expect(screen.queryByText('MEMBER')).not.toBeInTheDocument();
    expect(screen.queryByRole('list', { name: 'Account statistics' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Request deletion' })).not.toBeInTheDocument();
  });

  it('offers no API key, since the backend issues none', async () => {
    stubApi({ 'GET /api/v1/auth/me': { status: 200, body: ME } });

    renderProfile();

    expect(await screen.findByRole('heading', { name: 'Identity' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'API key' })).not.toBeInTheDocument();
    expect(screen.queryByText(/Authorization: Bearer/)).not.toBeInTheDocument();
  });

  it('shows a dash when /me has no status', async () => {
    const { status: _status, ...withoutStatus } = ME;
    stubApi({ 'GET /api/v1/auth/me': { status: 200, body: withoutStatus } });

    renderProfile();

    expect(await screen.findByText('—')).toBeInTheDocument();
  });

  it('shows the stats when VITE_ACCOUNT_STATS_ENABLED is true', async () => {
    vi.stubEnv('VITE_ACCOUNT_STATS_ENABLED', 'true');
    stubApi({ 'GET /api/v1/auth/me': { status: 200, body: ME } });

    renderProfile();

    expect(await screen.findByRole('list', { name: 'Account statistics' })).toBeInTheDocument();
  });

  describe('delete-account zone', () => {
    const STATUS = 'GET /api/v1/me/account-deletion';
    const ELIGIBILITY = 'GET /api/v1/me/account-deletion/eligibility';
    const REQUEST = 'POST /api/v1/me/account-deletion';
    const STEP_UP = 'POST /api/v1/me/step-up';
    const ON: FakeReply = { status: 200, body: { enabled: true, gracePeriodDays: 30, state: 'NONE' } };
    const ELIGIBLE: FakeReply = { status: 200, body: { eligible: true, reasons: [] } };
    const PROOF: FakeReply = {
      status: 200,
      body: { stepUpProof: 'proof-1', expiresAt: '2026-11-11T10:05:00Z' },
    };
    const ACCEPTED: FakeReply = {
      status: 202,
      body: {
        requestId: 'r-1',
        state: 'PENDING',
        requestedAt: '2026-11-11T10:00:00Z',
        scheduledAt: '2026-12-11T10:00:00Z',
      },
    };
    let restoreDialog: () => void;

    beforeEach(() => {
      restoreDialog = stubModalDialog();
    });
    afterEach(() => restoreDialog());

    function problem(status: number, code: string, extra: object = {}): FakeReply {
      return { status, body: { status, code, detail: 'server wording', ...extra } };
    }

    function renderWithPendingScreen() {
      return renderRoute(
        '/profile',
        '/profile',
        <ProfilePage />,
        <Route path="/account-deletion" element={<p>Pending screen</p>} />,
      );
    }

    async function confirmAndEnterPassword(user: ReturnType<typeof userEvent.setup>) {
      await user.click(await screen.findByRole('button', { name: 'Request deletion' }));
      await user.click(await screen.findByRole('button', { name: 'Continue' }));
      await user.type(await screen.findByLabelText('Password'), 'correct horse');
      await user.click(screen.getByRole('button', { name: 'Confirm' }));
    }

    it('lists the reasons of a blocked member and offers only Close', async () => {
      stubApi({
        ...OWN_ROUTES,
        [STATUS]: ON,
        [ELIGIBILITY]: {
          status: 200,
          body: {
            eligible: false,
            reasons: [{ key: 'workflow.active_run', message: 'A run is still in progress.' }],
          },
        },
      });
      const user = userEvent.setup();
      renderProfile();

      await user.click(await screen.findByRole('button', { name: 'Request deletion' }));

      const dialog = await screen.findByRole('dialog', { name: 'Delete your account' });
      expect(await within(dialog).findByText('A run is still in progress.')).toBeInTheDocument();
      expect(within(dialog).getAllByRole('button').map((button) => button.textContent)).toEqual(['Close']);
      await user.click(within(dialog).getByRole('button', { name: 'Close' }));
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    });

    it('asks again at every opening', async () => {
      let blocked = true;
      stubApi({
        ...OWN_ROUTES,
        [STATUS]: ON,
        [ELIGIBILITY]: () =>
          blocked
            ? { status: 200, body: { eligible: false, reasons: [{ key: 'k', message: 'Busy.' }] } }
            : ELIGIBLE,
      });
      const user = userEvent.setup();
      renderProfile();

      await user.click(await screen.findByRole('button', { name: 'Request deletion' }));
      await user.click(await screen.findByRole('button', { name: 'Close' }));
      blocked = false;
      await user.click(screen.getByRole('button', { name: 'Request deletion' }));

      expect(await screen.findByRole('button', { name: 'Continue' })).toBeInTheDocument();
      expect(screen.queryByText('Busy.')).not.toBeInTheDocument();
    });

    it('sends an eligible member through the password step to the pending screen', async () => {
      const calls = stubApi({
        ...OWN_ROUTES,
        [STATUS]: ON,
        [ELIGIBILITY]: ELIGIBLE,
        [STEP_UP]: PROOF,
        [REQUEST]: ACCEPTED,
      });
      const user = userEvent.setup();
      renderWithPendingScreen();

      await user.click(await screen.findByRole('button', { name: 'Request deletion' }));
      const dialog = await screen.findByRole('dialog', { name: 'Delete your account' });
      expect(await within(dialog).findByText(/You can cancel for 30 days/)).toBeInTheDocument();
      expect(within(dialog).getByText(/Every device, including this one, is signed out/)).toBeInTheDocument();
      await user.click(within(dialog).getByRole('button', { name: 'Continue' }));
      await user.type(await screen.findByLabelText('Password'), 'correct horse');
      await user.click(screen.getByRole('button', { name: 'Confirm' }));

      expect(await screen.findByText('Pending screen')).toBeInTheDocument();
      expect(calls.find((call) => call.path === '/api/v1/me/step-up')?.body).toEqual({
        password: 'correct horse',
        purpose: 'ACCOUNT_DELETION',
      });
      expect(calls.find((call) => call.method === 'POST' && call.path === '/api/v1/me/account-deletion')?.body).toEqual({
        stepUpProof: 'proof-1',
      });
    });

    it('stays on the profile page and lists the reasons when the submit is blocked', async () => {
      stubApi({
        ...OWN_ROUTES,
        [STATUS]: ON,
        [ELIGIBILITY]: ELIGIBLE,
        [STEP_UP]: PROOF,
        [REQUEST]: problem(409, 'account.deletion.blocked', {
          reasons: [{ key: 'support.open_request', message: 'A support request is still open.' }],
        }),
      });
      const user = userEvent.setup();
      renderWithPendingScreen();

      await confirmAndEnterPassword(user);

      expect(await screen.findByText('A support request is still open.')).toBeInTheDocument();
      expect(screen.queryByText('Pending screen')).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Continue' })).not.toBeInTheDocument();
    });

    it.each([
      [409, 'account.deletion.staffAccount', 'Staff and administrator accounts are removed by an administrator.'],
      [
        403,
        'STEP_UP_REQUIRED',
        'Your password confirmation expired or was already used. Continue to confirm it again.',
      ],
      [503, 'account.deletion.eligibilityUnavailable', 'We could not check your account right now. Try again shortly.'],
      [500, 'internal', 'We could not send the request. Try again.'],
    ])('shows a message for a %i %s refusal and stays on the profile page', async (status, code, message) => {
      stubApi({
        ...OWN_ROUTES,
        [STATUS]: ON,
        [ELIGIBILITY]: ELIGIBLE,
        [STEP_UP]: PROOF,
        [REQUEST]: problem(status, code),
      });
      const user = userEvent.setup();
      renderWithPendingScreen();

      await confirmAndEnterPassword(user);

      const dialog = await screen.findByRole('dialog', { name: 'Delete your account' });
      expect(await within(dialog).findByRole('alert')).toHaveTextContent(message);
      expect(screen.queryByText('Pending screen')).not.toBeInTheDocument();
    });

    function held() {
      let release: (reply: FakeReply) => void = () => undefined;
      const promise = new Promise<FakeReply>((resolve) => {
        release = resolve;
      });
      return { promise, release };
    }

    it('keeps the dialog open on Escape while the request is being sent', async () => {
      const request = held();
      stubApi({
        ...OWN_ROUTES,
        [STATUS]: ON,
        [ELIGIBILITY]: ELIGIBLE,
        [STEP_UP]: PROOF,
        [REQUEST]: () => request.promise,
      });
      const user = userEvent.setup();
      renderWithPendingScreen();

      await confirmAndEnterPassword(user);
      const dialog = await screen.findByRole('dialog', { name: 'Delete your account' });
      await within(dialog).findByRole('button', { name: 'Submitting…' });
      await user.keyboard('{Escape}');

      expect(dialog).toHaveAttribute('open');
      request.release(ACCEPTED);
      expect(await screen.findByText('Pending screen')).toBeInTheDocument();
    });

    it('brings the dialog back when the browser closes it mid-request, and it can be closed and reopened afterwards', async () => {
      const request = held();
      stubApi({
        ...OWN_ROUTES,
        [STATUS]: ON,
        [ELIGIBILITY]: ELIGIBLE,
        [STEP_UP]: PROOF,
        [REQUEST]: () => request.promise,
      });
      const user = userEvent.setup();
      renderWithPendingScreen();

      await confirmAndEnterPassword(user);
      const dialog = await screen.findByRole('dialog', { name: 'Delete your account' });
      await within(dialog).findByRole('button', { name: 'Submitting…' });
      act(() => (dialog as HTMLDialogElement).close());

      expect(dialog).toHaveAttribute('open');
      request.release(problem(500, 'internal'));
      expect(await within(dialog).findByRole('alert')).toHaveTextContent(
        'We could not send the request. Try again.',
      );
      await user.click(within(dialog).getByRole('button', { name: 'Keep my account' }));
      await waitFor(() => expect(dialog).not.toHaveAttribute('open'));
      await user.click(screen.getByRole('button', { name: 'Request deletion' }));
      expect(await screen.findByRole('dialog', { name: 'Delete your account' })).toHaveAttribute('open');
    });

    it('waits for the new answer at every opening instead of showing the previous one', async () => {
      let next: Promise<FakeReply> | null = null;
      stubApi({
        ...OWN_ROUTES,
        [STATUS]: ON,
        [ELIGIBILITY]: () =>
          next ?? { status: 200, body: { eligible: false, reasons: [{ key: 'k', message: 'Busy.' }] } },
      });
      const user = userEvent.setup();
      renderProfile();

      await user.click(await screen.findByRole('button', { name: 'Request deletion' }));
      await user.click(await screen.findByRole('button', { name: 'Close' }));
      const answer = held();
      next = answer.promise;
      await user.click(screen.getByRole('button', { name: 'Request deletion' }));

      expect(await screen.findByRole('status')).toHaveTextContent('Checking your account…');
      expect(screen.queryByText('Busy.')).not.toBeInTheDocument();
      answer.release(ELIGIBLE);
      expect(await screen.findByRole('button', { name: 'Continue' })).toBeInTheDocument();
    });

    describe('background refetch of the eligibility check', () => {
      const ELIGIBILITY_KEY = ['accountDeletion', 'eligibility'];

      it('keeps the confirm view and the typed password', async () => {
        let next: Promise<FakeReply> | null = null;
        stubApi({
          ...OWN_ROUTES,
          [STATUS]: ON,
          [ELIGIBILITY]: () => next ?? ELIGIBLE,
          [STEP_UP]: PROOF,
        });
        const user = userEvent.setup();
        const { queryClient } = renderProfile();

        await user.click(await screen.findByRole('button', { name: 'Request deletion' }));
        const dialog = await screen.findByRole('dialog', { name: 'Delete your account' });
        const keep = await within(dialog).findByRole('button', { name: 'Keep my account' });
        const proceed = within(dialog).getByRole('button', { name: 'Continue' });
        await user.click(proceed);
        await user.type(await screen.findByLabelText('Password'), 'half typed');

        const answer = held();
        next = answer.promise;
        void queryClient.refetchQueries({ queryKey: ELIGIBILITY_KEY });
        await waitFor(() => expect(queryClient.isFetching({ queryKey: ELIGIBILITY_KEY })).toBe(1));

        expect(screen.queryByRole('status')).not.toBeInTheDocument();
        expect(keep).toBeInTheDocument();
        expect(proceed).toBeInTheDocument();
        expect(screen.getByLabelText('Password')).toHaveValue('half typed');
        answer.release(ELIGIBLE);
        await settled(queryClient);
        expect(screen.queryByRole('status')).not.toBeInTheDocument();
        expect(keep).toBeInTheDocument();
        expect(screen.getByLabelText('Password')).toHaveValue('half typed');
      });

      it('moves to the reasons, with focus kept inside the dialog, when a refetch says the member is blocked', async () => {
        let next: Promise<FakeReply> | null = null;
        stubApi({ ...OWN_ROUTES, [STATUS]: ON, [ELIGIBILITY]: () => next ?? ELIGIBLE });
        const user = userEvent.setup();
        const { queryClient } = renderProfile();

        await user.click(await screen.findByRole('button', { name: 'Request deletion' }));
        const dialog = await screen.findByRole('dialog', { name: 'Delete your account' });
        (await within(dialog).findByRole('button', { name: 'Continue' })).focus();

        const answer = held();
        next = answer.promise;
        void queryClient.refetchQueries({ queryKey: ELIGIBILITY_KEY });
        await waitFor(() => expect(queryClient.isFetching({ queryKey: ELIGIBILITY_KEY })).toBe(1));
        expect(screen.queryByRole('status')).not.toBeInTheDocument();
        answer.release({
          status: 200,
          body: { eligible: false, reasons: [{ key: 'k', message: 'A run is still in progress.' }] },
        });

        expect(await within(dialog).findByText('A run is still in progress.')).toBeInTheDocument();
        expect(within(dialog).queryByRole('button', { name: 'Continue' })).not.toBeInTheDocument();
        expect(within(dialog).getByRole('button', { name: 'Close' })).toHaveFocus();
      });
    });
  });

  it('saves the display name through PUT /auth/profile', async () => {
    const calls = stubApi({
      ...OWN_ROUTES,
      'PUT /api/v1/auth/profile': { status: 200, body: { ...OWN, name: 'Renamed' } },
    });
    const user = userEvent.setup();
    renderProfile();

    const input = await screen.findByLabelText('Display name');
    await waitFor(() => expect(screen.getByLabelText('First name')).toHaveValue('Mem'));
    await user.clear(input);
    await user.type(input, 'Renamed');
    await user.clear(screen.getByLabelText('Last name'));
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(await screen.findByText('✓ Saved')).toBeInTheDocument();
    const put = calls.find((call) => call.method === 'PUT');
    expect(put?.body).toEqual({ name: 'Renamed', firstName: 'Mem', lastName: '' });
  });

  it('offers no name form, only Retry, when the own profile fails to load', async () => {
    stubApi({
      ...OWN_ROUTES,
      'GET /api/v1/auth/profile': { status: 500, body: { status: 500, detail: 'boom' } },
    });

    renderProfile();

    expect(await screen.findByRole('button', { name: 'Retry' })).toBeInTheDocument();
    expect(screen.queryByLabelText('Display name')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('First name')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save changes' })).not.toBeInTheDocument();
  });

  it('shows the server detail when saving fails', async () => {
    stubApi({
      ...OWN_ROUTES,
      'PUT /api/v1/auth/profile': { status: 400, body: { status: 400, detail: 'Name too long' } },
    });
    const user = userEvent.setup();
    renderProfile();

    await waitFor(() => expect(screen.getByLabelText('First name')).toHaveValue('Mem'));
    await user.click(await screen.findByRole('button', { name: 'Save changes' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Name too long');
  });

  it('saves a username and shows the lock date', async () => {
    let saved = false;
    const calls = stubApi({
      ...OWN_ROUTES,
      'GET /api/v1/auth/profile': () => ({
        status: 200,
        body: saved
          ? { ...OWN, username: 'new_name', usernameNextChangeAt: '2099-01-15T12:00:00Z' }
          : OWN,
      }),
      'PUT /api/v1/auth/profile/username': () => {
        saved = true;
        return { status: 200, body: { ...OWN, username: 'new_name' } };
      },
    });
    const user = userEvent.setup();
    renderProfile();

    const input = await screen.findByLabelText('Username');
    await waitFor(() => expect(input).toHaveValue('member_one'));
    await user.clear(input);
    await user.type(input, 'new_name');
    await user.click(screen.getByRole('button', { name: 'Save username' }));

    expect(await screen.findByText('✓ Username saved')).toBeInTheDocument();
    expect(calls.find((call) => call.path.endsWith('/username'))?.body).toEqual({
      username: 'new_name',
    });
    await waitFor(() => expect(screen.getByLabelText('Username')).toBeDisabled());
    expect(screen.getByText(/You can change it again on/)).toHaveTextContent('2099');
  });

  it('shows a taken name under the username field', async () => {
    stubApi({
      ...OWN_ROUTES,
      'PUT /api/v1/auth/profile/username': {
        status: 409,
        body: { status: 409, detail: 'That username is taken.' },
      },
    });
    const user = userEvent.setup();
    renderProfile();

    const input = await screen.findByLabelText('Username');
    await waitFor(() => expect(input).toHaveValue('member_one'));
    await user.clear(input);
    await user.type(input, 'taken_one');
    await user.click(screen.getByRole('button', { name: 'Save username' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('That username is taken.');
    expect(input).toHaveAttribute('aria-invalid', 'true');
  });

  it('reverts a privacy choice when the save fails', async () => {
    stubApi({
      ...OWN_ROUTES,
      'PATCH /api/v1/auth/profile/preferences/privacy': {
        status: 500,
        body: { status: 500, detail: 'boom' },
      },
    });
    const user = userEvent.setup();
    renderProfile();

    const select = await screen.findByLabelText('Who can see your profile');
    await waitFor(() => expect(select).toBeEnabled());
    await user.selectOptions(select, 'only_me');

    expect(await screen.findByRole('alert')).toHaveTextContent('boom');
    await waitFor(() => expect(select).toHaveValue('everyone'));
  });

  it('restores the saved name on Cancel', async () => {
    stubApi(OWN_ROUTES);
    const user = userEvent.setup();
    renderProfile();

    const input = await screen.findByLabelText('Display name');
    await waitFor(() => expect(screen.getByLabelText('First name')).toHaveValue('Mem'));
    await user.clear(input);
    await user.type(input, 'Draft');
    await user.click(screen.getByRole('button', { name: 'Cancel' }));

    await waitFor(() => expect(input).toHaveValue('Member One'));
  });

  it('keeps a heading and offers Retry when /me fails', async () => {
    stubApi({ 'GET /api/v1/auth/me': { status: 500, body: { status: 500, detail: 'boom' } } });

    renderProfile();

    expect(await screen.findByRole('button', { name: 'Retry' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'Profile' })).toBeInTheDocument();
  });

  it('shows a role or status code it does not know as sent', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: { ...ME, role: 'AUDITOR', status: 'PENDING' } },
    });

    renderProfile();

    expect(await screen.findByText('PENDING')).toBeInTheDocument();
    expect(screen.getAllByText('AUDITOR')).toHaveLength(2);
  });

  it('shows a custom role by the name /me carries instead of its code', async () => {
    stubApi({
      'GET /api/v1/auth/me': {
        status: 200,
        body: { ...ME, role: 'HO_TRO_NANG_CAO', roleName: 'Advanced support' },
      },
    });

    renderProfile();

    expect(await screen.findAllByText('Advanced support')).toHaveLength(2);
    expect(screen.queryByText('HO_TRO_NANG_CAO')).not.toBeInTheDocument();
  });

  describe('in Vietnamese', () => {
    beforeEach(async () => {
      await i18n.changeLanguage('vi');
    });
    afterEach(async () => {
      await i18n.changeLanguage('en');
    });

    it('labels the role and status in Vietnamese', async () => {
      stubApi({ 'GET /api/v1/auth/me': { status: 200, body: { ...ME, status: 'SUSPENDED' } } });

      renderProfile();

      expect(await screen.findByText(viAuth.profile.status.SUSPENDED)).toBeInTheDocument();
      expect(screen.queryByText('SUSPENDED')).not.toBeInTheDocument();
      expect(screen.getAllByText(viAuth.profile.role.MEMBER)).toHaveLength(2);
    });

    it('shows the generic retry message in Vietnamese when saving never reaches the server', async () => {
      stubApi({
        ...OWN_ROUTES,
        'PUT /api/v1/auth/profile': () => {
          throw new TypeError('Failed to fetch');
        },
      });
      const user = userEvent.setup();
      renderProfile();

      await user.click(await screen.findByRole('button', { name: viAuth.profile.save }));

      expect(await screen.findByRole('alert')).toHaveTextContent(
        'Không thực hiện được yêu cầu. Hãy thử lại.',
      );
    });
  });
});
