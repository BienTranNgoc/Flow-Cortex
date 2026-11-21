import { setToken } from '@flow-cortex/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { stubApi, type Handler, type RecordedCall } from '../../test/fakeApi';
import { OnboardingFlow } from './OnboardingFlow';
import { requestReplay, resetOnboardingSession } from './onboardingSession';

const FEATURES = {
  emailConfirmation: false,
  phoneVerification: false,
  uploadContentCheck: false,
  runCancellation: false,
  credits: false,
  passwordReset: false,
  supportWidget: false,
  supportAssistant: false,
  onboarding: true,
};

const ANSWERED = {
  purposes: ['research'],
  source: 'google',
  aiLevel: 'tried',
  industries: ['tech'],
  contentTypes: ['text'],
  usageType: 'team',
};

function status(overrides: Record<string, unknown> = {}) {
  return {
    completed: false,
    completedAt: null,
    purposes: null,
    source: null,
    aiLevel: null,
    industries: null,
    contentTypes: null,
    usageType: null,
    progress: {},
    firstRunDone: false,
    open: true,
    entry: 'first',
    ...overrides,
  };
}

/** jsdom has no `showModal`/`close`; Escape fires `cancel` the way a browser does. */
function stubModalDialog() {
  const proto = HTMLDialogElement.prototype as unknown as Record<string, unknown>;
  Object.defineProperties(proto, {
    showModal: {
      configurable: true,
      value(this: HTMLDialogElement) {
        this.setAttribute('open', '');
      },
    },
    close: {
      configurable: true,
      value(this: HTMLDialogElement) {
        this.removeAttribute('open');
      },
    },
  });
  const onEscape = (event: KeyboardEvent) => {
    const open = document.querySelector('dialog[open]');
    if (event.key === 'Escape' && open instanceof HTMLDialogElement) {
      open.dispatchEvent(new Event('cancel', { cancelable: true }));
    }
  };
  document.addEventListener('keydown', onEscape);
  return () => {
    document.removeEventListener('keydown', onEscape);
    delete proto.showModal;
    delete proto.close;
  };
}

function renderFlow(routes: Record<string, Handler>, path = '/') {
  const calls = stubApi({
    'GET /api/v1/features': { status: 200, body: FEATURES },
    'POST /api/v1/onboarding/progress': { status: 200, body: { progress: {} } },
    'PATCH /api/v1/onboarding/quiz': { status: 200, body: { completed: false, completedAt: null } },
    ...routes,
  });
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <nav className="sidebar-nav" />
        <OnboardingFlow />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return calls;
}

const progressWrites = (calls: RecordedCall[]) =>
  calls.filter((call) => call.path === '/api/v1/onboarding/progress').map((call) => call.body);

describe('OnboardingFlow', () => {
  let restoreDialog: () => void;

  beforeAll(() => {
    restoreDialog = stubModalDialog();
  });
  afterAll(() => restoreDialog());
  beforeEach(() => setToken('t', true));
  afterEach(() => {
    cleanup();
    setToken(null);
    act(() => resetOnboardingSession());
    vi.unstubAllGlobals();
  });

  it('walks Welcome, the tour and the survey intro to question 1, storing each step', async () => {
    const user = userEvent.setup();
    const calls = renderFlow({
      'GET /api/v1/onboarding/status': { status: 200, body: status() },
    });

    expect(await screen.findByRole('dialog', { name: 'Welcome to FlowCortex' })).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Start' }));
    expect(await screen.findByRole('dialog', { name: 'Find your way around' })).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await user.click(await screen.findByRole('button', { name: 'Start survey' }));
    expect(
      await screen.findByRole('dialog', { name: 'What do you want to use FlowCortex for?' }),
    ).toBeVisible();

    await waitFor(() =>
      expect(progressWrites(calls)).toEqual([
        { step: 'tour:home' },
        { step: 'survey-intro' },
        { step: 'quiz', question: 1 },
      ]),
    );
  });

  it('resumes at the stored question', async () => {
    renderFlow({
      'GET /api/v1/onboarding/status': {
        status: 200,
        body: status({ progress: { v: 2, step: 'quiz', question: 4 } }),
      },
    });
    expect(
      await screen.findByRole('dialog', { name: 'Which field do you work in?' }),
    ).toBeVisible();
  });

  it('resumes a deferred Done without the survey line and Back opens the survey intro', async () => {
    const user = userEvent.setup();
    renderFlow({
      'GET /api/v1/onboarding/status': {
        status: 200,
        body: status({
          progress: { v: 2, step: 'done', surveyDeferredAt: '2026-11-10T09:00:00Z' },
        }),
      },
    });
    expect(await screen.findByRole('dialog', { name: "You're all set" })).toBeVisible();
    expect(screen.queryByText('Answered the survey')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Back' }));
    expect(await screen.findByRole('dialog', { name: 'Tell us about your work' })).toBeVisible();
  });

  it('resumes Done listing the survey when it was deferred but every core answer is stored', async () => {
    renderFlow({
      'GET /api/v1/onboarding/status': {
        status: 200,
        body: status({
          ...ANSWERED,
          progress: { v: 2, step: 'done', surveyDeferredAt: '2026-11-10T09:00:00Z' },
        }),
      },
    });
    expect(await screen.findByRole('dialog', { name: "You're all set" })).toBeVisible();
    expect(screen.getByText('Answered the survey')).toBeVisible();
  });

  it('closes on Escape without a write and stays closed for the session', async () => {
    const user = userEvent.setup();
    const calls = renderFlow({
      'GET /api/v1/onboarding/status': { status: 200, body: status() },
    });
    await screen.findByRole('dialog', { name: 'Welcome to FlowCortex' });
    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(progressWrites(calls)).toEqual([]);
  });

  it('skip finishes the journey with one write', async () => {
    const user = userEvent.setup();
    const calls = renderFlow({
      'GET /api/v1/onboarding/status': { status: 200, body: status() },
    });
    await screen.findByRole('dialog', { name: 'Welcome to FlowCortex' });
    await user.click(screen.getByRole('button', { name: 'Skip' }));
    await waitFor(() => expect(progressWrites(calls)).toEqual([{ finished: true }]));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('closes without an error when the progress write answers 409', async () => {
    const user = userEvent.setup();
    renderFlow({
      'GET /api/v1/onboarding/status': { status: 200, body: status() },
      'POST /api/v1/onboarding/progress': { status: 409, body: { status: 409 } },
    });
    await screen.findByRole('dialog', { name: 'Welcome to FlowCortex' });
    await user.click(screen.getByRole('button', { name: 'Start' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('saves null for an unpicked answer and the picked list for a multi-select', async () => {
    const user = userEvent.setup();
    const calls = renderFlow({
      'GET /api/v1/onboarding/status': {
        status: 200,
        body: status({ progress: { v: 2, step: 'quiz', question: 2 } }),
      },
    });
    await screen.findByRole('dialog', { name: 'How did you hear about us?' });
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await screen.findByRole('dialog', { name: 'How familiar are you with AI tools?' });
    await user.click(screen.getByRole('radio', { name: "I'm just starting out" }));
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await screen.findByRole('dialog', { name: 'Which field do you work in?' });
    await user.click(screen.getByRole('button', { name: 'Technology' }));
    await user.click(screen.getByRole('button', { name: 'Marketing' }));
    expect(screen.getByRole('button', { name: 'Marketing' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await screen.findByRole('dialog', { name: 'What kind of content do you work with?' });

    const patches = calls.filter((call) => call.method === 'PATCH').map((call) => call.body);
    expect(patches).toEqual([
      { source: null },
      { aiLevel: 'beginner' },
      { industries: ['marketing', 'tech'] },
    ]);
  });

  it('keeps the question with an error and Retry when the save fails, then continues', async () => {
    const user = userEvent.setup();
    let attempt = 0;
    renderFlow({
      'GET /api/v1/onboarding/status': {
        status: 200,
        body: status({ progress: { v: 2, step: 'quiz', question: 3 } }),
      },
      'PATCH /api/v1/onboarding/quiz': () =>
        ++attempt === 1
          ? { status: 500, body: { status: 500 } }
          : { status: 200, body: { completed: false, completedAt: null } },
    });
    await screen.findByRole('dialog', { name: 'How familiar are you with AI tools?' });
    await user.click(screen.getByRole('radio', { name: 'I build AI products' }));
    await user.click(screen.getByRole('button', { name: 'Next' }));

    expect(await screen.findByRole('alert')).toHaveTextContent("couldn't save");
    expect(screen.getByRole('radio', { name: 'I build AI products' })).toBeChecked();
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    expect(
      await screen.findByRole('dialog', { name: 'Which field do you work in?' }),
    ).toBeVisible();
  });

  it('finishes the last question with finished: true and ends on Done', async () => {
    const user = userEvent.setup();
    const calls = renderFlow({
      'GET /api/v1/onboarding/status': {
        status: 200,
        body: status({ progress: { v: 2, step: 'quiz', question: 6 } }),
      },
    });
    await screen.findByRole('dialog', { name: 'Who will use FlowCortex?' });
    await user.click(screen.getByRole('radio', { name: 'My team' }));
    await user.click(screen.getByRole('button', { name: 'Finish' }));
    await screen.findByRole('dialog', { name: "You're all set" });
    await user.click(screen.getByRole('button', { name: 'Start using FlowCortex' }));

    expect(calls.find((call) => call.method === 'PATCH')?.body).toEqual({
      usageType: 'team',
      finished: true,
    });
    await waitFor(() =>
      expect(progressWrites(calls)).toEqual([{ step: 'done' }, { step: 'done', finished: true }]),
    );
  });

  it('sends nothing while the feature is off', async () => {
    const calls = renderFlow({
      'GET /api/v1/features': { status: 200, body: { ...FEATURES, onboarding: false } },
    });
    await waitFor(() => expect(calls.some((c) => c.path === '/api/v1/features')).toBe(true));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(calls.some((c) => c.path.startsWith('/api/v1/onboarding'))).toBe(false);
  });

  it('replays Welcome, the tour and Done when every core answer is stored, storing no progress', async () => {
    const user = userEvent.setup();
    const calls = renderFlow({
      'GET /api/v1/onboarding/status': {
        status: 200,
        body: status({
          ...ANSWERED,
          entry: null,
          open: false,
          progress: { v: 2, finishedAt: 'x' },
        }),
      },
    });
    await waitFor(() => expect(calls.some((c) => c.path.endsWith('/status'))).toBe(true));
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 50));
    });
    act(() => requestReplay());

    await screen.findByRole('dialog', { name: 'Welcome to FlowCortex' });
    await user.click(screen.getByRole('button', { name: 'Start' }));
    await screen.findByRole('dialog', { name: 'Find your way around' });
    await user.click(screen.getByRole('button', { name: 'Next' }));
    expect(await screen.findByRole('dialog', { name: "You're all set" })).toBeVisible();
    expect(progressWrites(calls)).toEqual([]);
  });
});
