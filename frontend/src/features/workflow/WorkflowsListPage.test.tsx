import { setToken, type WorkflowResponse } from '@flow-cortex/api-client';
import { act, screen, waitFor } from '@testing-library/react';
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

  it('renders a table with status pills, last-run, step count, and run / edit / more actions', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': { status: 200, body: [DOUYIN, ECHO, FAIL, PRODUCT_VIDEO] },
      'GET /api/v1/capabilities': capabilities(true),
    });
    renderRoute('/workflows', '/workflows', <WorkflowsListPage />);

    expect(await screen.findByRole('heading', { name: 'Workflows' })).toBeInTheDocument();
    expect(await screen.findByText(/Welcome back/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Import' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /\+ Create Workflow/ })).toBeInTheDocument();
    expect(screen.getByPlaceholderText(/Search workflows/i)).toBeInTheDocument();

    // Table header columns from the spec.
    const table = await screen.findByRole('region', { name: /workflows table/i });
    expect(withinTable(table, 'Name')).toBeInTheDocument();
    expect(withinTable(table, 'Status')).toBeInTheDocument();
    expect(withinTable(table, 'Last run')).toBeInTheDocument();
    expect(withinTable(table, 'Steps')).toBeInTheDocument();
    expect(withinTable(table, 'Actions')).toBeInTheDocument();

    // Status pills match the data.
    expect(screen.getByText('Active')).toBeInTheDocument();
    expect(screen.getByText('Failed')).toBeInTheDocument();
    expect(screen.getByText('Review required')).toBeInTheDocument();
    expect(screen.getByText('Inactive')).toBeInTheDocument();
  });
});

function withinTable(scope: HTMLElement, text: string): HTMLElement {
  const node = [...scope.querySelectorAll('th')].find((th) => th.textContent?.trim() === text);
  if (!node) throw new Error(`column ${text} not found`);
  return node as HTMLElement;
}
