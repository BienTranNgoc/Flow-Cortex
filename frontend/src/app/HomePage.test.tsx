import { setToken, type WorkflowResponse, type WorkflowStepResponse } from '@flow-cortex/api-client';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route } from 'react-router-dom';
import { renderRoute, stubApi } from '../test/fakeApi';
import { HomePage } from './HomePage';

const ME = { id: 'u1', email: 'member1@flowcortex.dev', name: 'Member One', role: 'MEMBER' };

function workflow(
  id: string,
  name: string,
  steps: WorkflowStepResponse[],
): WorkflowResponse {
  return { id, name, isShared: true, available: true, allowFork: false, forkCount: 0, steps };
}

const DOUYIN_STEPS: WorkflowStepResponse[] = [
  { id: 'a-1', stepOrder: 1, name: 'echo_input', type: 'echo', mode: 'inline' },
  { id: 'a-2', stepOrder: 2, name: 'approval_checkpoint', type: 'APPROVAL_CHECKPOINT', mode: 'inline' },
  { id: 'a-3', stepOrder: 3, name: 'echo_output', type: 'echo', mode: 'inline' },
];

describe('HomePage — launchpad cards', () => {
  beforeEach(() => setToken('t', true));
  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
  });

  function renderHome() {
    return renderRoute(
      '/',
      '/',
      <HomePage />,
      <Route path="/workflows/:id" element={<p>Detail page</p>} />,
    );
  }

  it('renders one card per workflow returned by the API', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': {
        status: 200,
        body: [
          workflow('wf-a', 'Douyin Video Repurpose', DOUYIN_STEPS),
          workflow('wf-b', 'Echo Demo (all steps succeed)', [
            { id: 'b-1', stepOrder: 1, name: 'Echo step 1', type: 'echo', mode: 'inline' },
          ]),
          workflow('wf-c', 'Check a video file', [
            { id: 'c-1', stepOrder: 1, name: 'Inspect the file', type: 'probe', mode: 'remote' },
            { id: 'c-2', stepOrder: 2, name: 'Extract source audio', type: 'extract_audio', mode: 'remote' },
          ]),
        ],
      },
    });

    renderHome();

    expect(
      await screen.findByRole('heading', { name: 'Douyin Video Repurpose' }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('heading', { name: 'Echo Demo (all steps succeed)' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Check a video file' })).toBeInTheDocument();
    expect(screen.queryAllByText('Coming soon')).toHaveLength(0);
  });

  it('links every card to its API workflow by id', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': {
        status: 200,
        body: [workflow('wf-check', 'Check a video file', [])],
      },
    });

    renderHome();

    const link = await screen.findByRole('link', { name: /Open Check a video file/ });
    expect(link).toHaveAttribute('href', '/workflows/wf-check');
  });

  it('renders a stage chip per workflow step, with the step name', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': {
        status: 200,
        body: [workflow('wf-a', 'Douyin Video Repurpose', DOUYIN_STEPS)],
      },
    });

    renderHome();

    await screen.findByRole('heading', { name: 'Douyin Video Repurpose' });
    expect(screen.getByText('echo_input')).toBeInTheDocument();
    expect(screen.getByText('approval_checkpoint')).toBeInTheDocument();
    expect(screen.getByText('echo_output')).toBeInTheDocument();
  });

  it('does not render ETA, status, priority, or credit fields', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': {
        status: 200,
        body: [workflow('wf-a', 'A', [{ id: 'a-1', stepOrder: 1, name: 'Step', type: 'echo', mode: 'inline' }])],
      },
    });

    renderHome();

    await screen.findByRole('heading', { name: 'A' });
    expect(screen.queryByText(/^~\d+s$/)).not.toBeInTheDocument();
    expect(screen.queryByText(/GPU Credit/)).not.toBeInTheDocument();
    expect(screen.queryByText(/P[012]/)).not.toBeInTheDocument();
  });

  it('shows a load error with retry when the workflows API fails', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': { status: 500, body: { status: 500 } },
    });

    renderHome();

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load workflows');
  });

  it('shows the server detail in the inline error when the workflows request fails with one', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': {
        status: 503,
        body: { status: 503, detail: 'Workflows are paused for maintenance.' },
      },
    });

    renderHome();

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Workflows are paused for maintenance.');
    expect(alert).not.toHaveTextContent('Could not load workflows');
  });

  it('shows a retry block (not a redirect) when /me returns a 5xx', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 500, body: { detail: 'boom' } },
    });

    renderHome();

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load your profile');
  });

  it('renders the search bar and the filter chips, without the old hero', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': {
        status: 200,
        body: [workflow('wf-a', 'Douyin Video Repurpose', DOUYIN_STEPS)],
      },
    });

    renderHome();

    await screen.findByRole('heading', { name: 'Douyin Video Repurpose' });
    expect(screen.getByRole('search')).toBeInTheDocument();
    expect(screen.getByRole('group', { name: /filter workflows/i })).toBeInTheDocument();
    expect(screen.queryByRole('tablist')).not.toBeInTheDocument();
    expect(screen.queryByText(/CREATOR LAUNCHPAD/)).not.toBeInTheDocument();
  });

  it('greets a member by name and offers one primary action to /workflows', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: { ...ME, name: 'Lan Phạm' } },
      'GET /api/v1/workflows': { status: 200, body: [] },
    });

    const { container } = renderHome();

    expect(await screen.findByRole('heading', { level: 1, name: /Lan Phạm/ })).toBeInTheDocument();
    const start = screen.getByRole('link', { name: 'Start a workflow' });
    expect(start).toHaveAttribute('href', '/workflows');
    expect(start).toHaveClass('cortex-btn--primary');
    expect(container.querySelectorAll('.cortex-btn--primary')).toHaveLength(1);
    expect(container.querySelector('main')).toBeNull();
  });

  it('greets a member without a name with the nameless greeting', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: { ...ME, name: undefined } },
      'GET /api/v1/workflows': { status: 200, body: [] },
    });

    renderHome();

    const heading = await screen.findByRole('heading', { level: 1 });
    expect(heading).toHaveTextContent(/^Hello$/);
  });

  it('shows card placeholders in a busy grid region until the workflows arrive', async () => {
    let release: (reply: { status: number; body: unknown }) => void = () => undefined;
    const pending = new Promise<{ status: number; body: unknown }>((resolve) => {
      release = resolve;
    });
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': () => pending,
    });

    const { container } = renderHome();

    await screen.findByRole('heading', { level: 1 });
    const region = container.querySelector('[aria-busy="true"]');
    expect(region).not.toBeNull();
    expect(region?.querySelectorAll('.skeleton-card').length).toBeGreaterThan(0);

    release({ status: 200, body: [workflow('wf-a', 'Douyin Video Repurpose', DOUYIN_STEPS)] });

    await screen.findByRole('heading', { name: 'Douyin Video Repurpose' });
    expect(container.querySelector('[aria-busy="true"]')).toBeNull();
    expect(container.querySelector('.skeleton-card')).toBeNull();
  });

  it('keeps the greeting and retries the workflows request from the inline error', async () => {
    const user = userEvent.setup();
    let attempts = 0;
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': () => {
        attempts += 1;
        return attempts === 1
          ? { status: 500, body: { status: 500 } }
          : { status: 200, body: [workflow('wf-a', 'Douyin Video Repurpose', DOUYIN_STEPS)] };
      },
    });

    renderHome();

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not load workflows');
    expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('heading', { name: 'Douyin Video Repurpose' })).toBeInTheDocument();
    expect(attempts).toBe(2);
  });

  it('explains a search with no match and clears it from one action', async () => {
    const user = userEvent.setup();
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': {
        status: 200,
        body: [
          workflow('wf-a', 'Douyin Video Repurpose', DOUYIN_STEPS),
          workflow('wf-b', 'Echo Demo (all steps succeed)', []),
          workflow('wf-c', 'Check a video file', []),
        ],
      },
    });

    renderHome();

    await user.type(await screen.findByRole('searchbox'), 'zzz');
    expect(screen.getByText('No workflow matches your search.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Clear filters' }));
    expect(screen.getByRole('searchbox')).toHaveValue('');
    expect(screen.getAllByRole('link', { name: /^Open / })).toHaveLength(3);
  });

  it('offers the guide when the account has no workflows', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': { status: 200, body: [] },
    });

    renderHome();

    expect(await screen.findByText('There are no workflows yet.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Read the guide' })).toHaveAttribute('href', '/docs/guides');
  });

  it('filters cards by search and shows an empty state with a reset', async () => {
    const user = userEvent.setup();
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': {
        status: 200,
        body: [
          workflow('wf-a', 'Douyin Video Repurpose', DOUYIN_STEPS),
          workflow('wf-b', 'Echo Demo (all steps succeed)', []),
        ],
      },
    });

    renderHome();

    const search = await screen.findByRole('searchbox');
    await user.type(search, 'douyin');
    expect(
      screen.getByRole('heading', { name: 'Douyin Video Repurpose' }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('heading', { name: 'Echo Demo (all steps succeed)' }),
    ).not.toBeInTheDocument();
  });

  it('filters cards by category chip', async () => {
    const user = userEvent.setup();
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': {
        status: 200,
        body: [
          workflow('wf-a', 'Douyin Video Repurpose', DOUYIN_STEPS),
          workflow('wf-b', 'Echo Demo (all steps succeed)', []),
        ],
      },
    });

    renderHome();

    const demos = await screen.findByRole('button', { name: /Demos/ });
    expect(demos).toHaveAttribute('aria-pressed', 'false');
    await user.click(demos);
    expect(demos).toHaveAttribute('aria-pressed', 'true');
    expect(
      screen.getByRole('heading', { name: 'Echo Demo (all steps succeed)' }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('heading', { name: 'Douyin Video Repurpose' }),
    ).not.toBeInTheDocument();
  });

  it('shows a status badge and an ETA on each card', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': {
        status: 200,
        body: [workflow('wf-a', 'Douyin Video Repurpose', DOUYIN_STEPS)],
      },
    });

    renderHome();

    expect(await screen.findByText('Review required')).toBeInTheDocument();
    expect(screen.getByText('3 steps')).toBeInTheDocument();
    expect(screen.getByText(/^~\d/)).toBeInTheDocument();
  });

  it('shows a short non-technical description below each card', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': {
        status: 200,
        body: [
          workflow('wf-echo', 'Echo Demo (all steps succeed)', [
            { id: 'e-1', stepOrder: 1, name: 'Echo step 1', type: 'echo', mode: 'inline' },
          ]),
        ],
      },
    });

    renderHome();
    expect(
      await screen.findByText(/Try a simple two-step pipeline that just echoes your input back/),
    ).toBeInTheDocument();
  });
});
