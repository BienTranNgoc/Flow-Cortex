import { setToken, type WorkflowResponse } from '@flow-cortex/api-client';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, stubApi, type FakeReply } from '../../test/fakeApi';
import { WorkflowsListPage } from './WorkflowsListPage';

const PRODUCT_VIDEO_ID = 'pv';

const CHECK: WorkflowResponse = {
  id: 'w1',
  name: 'Check a video file',
  isShared: false,
  steps: [],
};

const PRODUCT_VIDEO: WorkflowResponse = {
  id: PRODUCT_VIDEO_ID,
  name: 'Product Video',
  isShared: true,
  steps: [],
};

const DOUYIN: WorkflowResponse = {
  id: 'douyin',
  name: 'Douyin Video Repurpose',
  isShared: true,
  steps: [
    { id: 's1', stepOrder: 1, name: 'echo_input', type: 'echo', mode: 'inline' },
    { id: 's2', stepOrder: 2, name: 'approval_checkpoint', type: 'APPROVAL_CHECKPOINT', mode: 'inline' },
  ],
};

const ECHO: WorkflowResponse = {
  id: 'echo',
  name: 'Echo Demo (all steps succeed)',
  isShared: false,
  steps: [{ id: 's3', stepOrder: 1, name: 'Echo', type: 'echo', mode: 'inline' }],
};

const FAIL: WorkflowResponse = {
  id: 'fail',
  name: 'Failing Demo (deliberate)',
  isShared: false,
  steps: [{ id: 's4', stepOrder: 1, name: 'throw', type: 'fail', mode: 'inline' }],
};

function capabilities(productVideoEnabled: boolean): FakeReply {
  return {
    status: 200,
    body: { mediaEnabled: true, productVideoEnabled, productVideoWorkflowId: PRODUCT_VIDEO_ID },
  };
}

const ME = {
  id: 'u1',
  email: 'member1@flowcortex.dev',
  name: 'Member One',
  role: 'MEMBER',
} as const;

describe('WorkflowsListPage', () => {
  beforeEach(() => {
    setToken('t', false);
  });
  afterEach(() => {
    setToken(null);
  });

  it('hides the workflow the capabilities name as Product Video when its flag is off', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': { status: 200, body: [CHECK, PRODUCT_VIDEO] },
      'GET /api/v1/capabilities': capabilities(false),
    });
    renderRoute('/workflows', '/workflows', <WorkflowsListPage />);

    expect(await screen.findByText('Check a video file')).toBeInTheDocument();
    expect(screen.queryByText('Product Video')).not.toBeInTheDocument();
  });

  it('never shows a switched-off Product Video entry while the capabilities are loading', async () => {
    let answer: (reply: FakeReply) => void = () => {};
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': { status: 200, body: [CHECK, PRODUCT_VIDEO] },
      'GET /api/v1/capabilities': () => new Promise<FakeReply>((resolve) => (answer = resolve)),
    });
    const { queryClient } = renderRoute('/workflows', '/workflows', <WorkflowsListPage />);

    await waitFor(() => expect(queryClient.isFetching()).toBe(1));
    await act(() => new Promise((resolve) => setTimeout(resolve, 20)));
    expect(screen.queryByText('Product Video')).not.toBeInTheDocument();

    answer(capabilities(false));
    expect(await screen.findByText('Check a video file')).toBeInTheDocument();
    expect(screen.queryByText('Product Video')).not.toBeInTheDocument();
  });

  it('shows the Product Video entry when the capabilities flag is on', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': { status: 200, body: [CHECK, PRODUCT_VIDEO] },
      'GET /api/v1/capabilities': capabilities(true),
    });
    renderRoute('/workflows', '/workflows', <WorkflowsListPage />);

    expect(await screen.findByText('Product Video')).toBeInTheDocument();
    expect(screen.getByText('Check a video file')).toBeInTheDocument();
  });

  it('renders a table with status pills, owner, step count, and run actions', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': { status: 200, body: [DOUYIN, ECHO, FAIL, PRODUCT_VIDEO] },
      'GET /api/v1/capabilities': capabilities(true),
    });
    renderRoute('/workflows', '/workflows', <WorkflowsListPage />);

    expect(await screen.findByRole('heading', { name: 'Workflows' })).toBeInTheDocument();
    expect(screen.getByPlaceholderText(/Search workflows/i)).toBeInTheDocument();

    // Table header columns from the spec.
    const table = await screen.findByRole('region', { name: /workflows table/i });
    expect(withinTable(table, 'Name')).toBeInTheDocument();
    expect(withinTable(table, 'Status')).toBeInTheDocument();
    expect(withinTable(table, 'Owner')).toBeInTheDocument();
    expect(withinTable(table, 'Steps')).toBeInTheDocument();
    expect(withinTable(table, 'Actions')).toBeInTheDocument();
    expect(screen.queryByText('Last run')).not.toBeInTheDocument();
    expect(screen.getAllByText('Shared team')).toHaveLength(2);
    expect(screen.getAllByText('You')).toHaveLength(2);

    // Status pills come from the steps: a definition has no active/failed run state.
    expect(screen.getByText('Review required')).toBeInTheDocument();
    expect(screen.getByText('Demo: failure')).toBeInTheDocument();
    expect(screen.getAllByText('Ready')).toHaveLength(2);
    expect(screen.queryByText('Active')).not.toBeInTheDocument();
    expect(screen.queryByText('Inactive')).not.toBeInTheDocument();
    expect(screen.queryByText('Failed')).not.toBeInTheDocument();
  });

  it('keeps only the workflows whose name matches the search', async () => {
    const user = userEvent.setup();
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': { status: 200, body: [DOUYIN, ECHO, FAIL] },
      'GET /api/v1/capabilities': capabilities(true),
    });
    renderRoute('/workflows', '/workflows', <WorkflowsListPage />);

    expect(await screen.findByText('Echo Demo (all steps succeed)')).toBeInTheDocument();
    await user.type(screen.getByPlaceholderText('Search workflows...'), 'douyin');

    expect(screen.getByText('Douyin Video Repurpose')).toBeInTheDocument();
    expect(screen.queryByText('Echo Demo (all steps succeed)')).not.toBeInTheDocument();
    expect(screen.queryByText('Failing Demo (deliberate)')).not.toBeInTheDocument();
    expect(screen.getByText('Showing 1 of 3 workflows')).toBeInTheDocument();
  });

  it('greets the member by name and offers no control the API cannot back', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': { status: 200, body: [DOUYIN, ECHO] },
      'GET /api/v1/capabilities': capabilities(true),
    });
    renderRoute('/workflows', '/workflows', <WorkflowsListPage />);

    expect(await screen.findByText('Echo Demo (all steps succeed)')).toBeInTheDocument();
    const welcome = screen.getByText(/Welcome back/);
    await waitFor(() => expect(within(welcome).getByText('Member')).toBeInTheDocument());
    expect(welcome).not.toHaveTextContent('Demo');

    expect(screen.queryByRole('button', { name: 'Import' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Create Workflow/ })).not.toBeInTheDocument();
    expect(screen.queryByText('Filter:')).not.toBeInTheDocument();
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /More actions/ })).not.toBeInTheDocument();
    expect(screen.queryByText(/Page 1 of 1/)).not.toBeInTheDocument();
    expect(screen.getByText('Showing 2 of 2 workflows')).toBeInTheDocument();
  });

  it('runs a workflow by opening its start page, in both layouts', async () => {
    const user = userEvent.setup();
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': { status: 200, body: [ECHO] },
      'GET /api/v1/capabilities': capabilities(true),
    });
    renderRoute('/workflows', '/workflows', <WorkflowsListPage />);

    const run = await screen.findByRole('link', { name: 'Run Echo Demo (all steps succeed)' });
    expect(run).toHaveAttribute('href', '/workflows/echo');

    await user.click(screen.getByRole('button', { name: 'Grid layout' }));

    expect(screen.getByRole('link', { name: 'Run Echo Demo (all steps succeed)' })).toHaveAttribute(
      'href',
      '/workflows/echo',
    );
  });

  it('scopes the list with pressed toggle buttons instead of tabs', async () => {
    const user = userEvent.setup();
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': { status: 200, body: [DOUYIN, ECHO] },
      'GET /api/v1/capabilities': capabilities(true),
    });
    renderRoute('/workflows', '/workflows', <WorkflowsListPage />);

    expect(await screen.findByText('Echo Demo (all steps succeed)')).toBeInTheDocument();
    expect(screen.queryByRole('tablist')).not.toBeInTheDocument();
    const scope = screen.getByRole('group', { name: 'Scope' });
    const mine = within(scope).getByRole('button', { name: 'Mine' });
    expect(mine).toHaveAttribute('aria-pressed', 'false');

    await user.click(mine);

    expect(mine).toHaveAttribute('aria-pressed', 'true');
    expect(within(scope).getByRole('button', { name: 'All' })).toHaveAttribute('aria-pressed', 'false');
    expect(screen.queryByText('Douyin Video Repurpose')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'List layout' })).toHaveAttribute('aria-pressed', 'true');
  });
});

function withinTable(scope: HTMLElement, text: string): HTMLElement {
  const node = [...scope.querySelectorAll('th')].find((th) => th.textContent?.trim() === text);
  if (!node) throw new Error(`column ${text} not found`);
  return node as HTMLElement;
}
