import { setToken, type WorkflowResponse } from '@flow-cortex/api-client';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import i18n from '../../lib/i18n';
import { renderRoute, stubApi, type FakeReply } from '../../test/fakeApi';
import { WorkflowsListPage } from './WorkflowsListPage';

const PRODUCT_VIDEO_ID = 'pv';

const CHECK: WorkflowResponse = {
  id: 'w1',
  name: 'Check a video file',
  isShared: false,
  available: true,
  allowFork: false,
  forkCount: 0,
  steps: [],
};

const PRODUCT_VIDEO: WorkflowResponse = {
  id: PRODUCT_VIDEO_ID,
  name: 'Product Video',
  isShared: true,
  available: true,
  allowFork: false,
  forkCount: 0,
  steps: [],
};

const DOUYIN: WorkflowResponse = {
  id: 'douyin',
  name: 'Douyin Video Repurpose',
  isShared: true,
  available: true,
  allowFork: false,
  forkCount: 0,
  steps: [
    { id: 's1', stepOrder: 1, name: 'echo_input', type: 'echo', mode: 'inline' },
    { id: 's2', stepOrder: 2, name: 'approval_checkpoint', type: 'APPROVAL_CHECKPOINT', mode: 'inline' },
  ],
};

const ECHO: WorkflowResponse = {
  id: 'echo',
  name: 'Echo Demo (all steps succeed)',
  isShared: false,
  available: true,
  allowFork: false,
  forkCount: 0,
  steps: [{ id: 's3', stepOrder: 1, name: 'Echo', type: 'echo', mode: 'inline' }],
};

const FAIL: WorkflowResponse = {
  id: 'fail',
  name: 'Failing Demo (deliberate)',
  isShared: false,
  available: true,
  allowFork: false,
  forkCount: 0,
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
    expect(table).toHaveAttribute('tabindex', '0');
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

  it('uses the singular for one workflow and one step', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': { status: 200, body: [ECHO] },
      'GET /api/v1/capabilities': capabilities(true),
    });
    renderRoute('/workflows', '/workflows', <WorkflowsListPage />);

    expect(await screen.findByText('Showing 1 of 1 workflow')).toBeInTheDocument();
    expect(screen.getAllByText('1 step').length).toBeGreaterThan(0);
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

  it('opens with the shared heading block and leaves the main landmark to the shell', async () => {
    const { container } = stubAndRender([DOUYIN]);

    expect(await screen.findByRole('heading', { level: 1, name: 'Workflows' })).toBeInTheDocument();
    expect(container.querySelector('main')).toBeNull();
    expect(container.querySelector('.page-heading h1')).not.toBeNull();
  });

  it('marks the list busy with row placeholders until the workflows arrive', async () => {
    let answer: (reply: FakeReply) => void = () => {};
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': () => new Promise<FakeReply>((resolve) => (answer = resolve)),
      'GET /api/v1/capabilities': capabilities(true),
    });
    const { container } = renderRoute('/workflows', '/workflows', <WorkflowsListPage />);

    await waitFor(() => expect(container.querySelector('[aria-busy="true"]')).not.toBeNull());
    expect(container.querySelectorAll('.skeleton-row').length).toBeGreaterThan(0);

    answer({ status: 200, body: [DOUYIN] });
    expect(await screen.findByText('Douyin Video Repurpose')).toBeInTheDocument();
    expect(container.querySelector('[aria-busy="true"]')).toBeNull();
    expect(container.querySelector('.skeleton-row')).toBeNull();
  });

  it('shows an inline error with Retry, and lists the workflows after the retry succeeds', async () => {
    const user = userEvent.setup();
    let workflowCalls = 0;
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': () => {
        workflowCalls += 1;
        return workflowCalls === 1
          ? { status: 503, body: { status: 503 } }
          : { status: 200, body: [DOUYIN, ECHO, FAIL] };
      },
      'GET /api/v1/capabilities': capabilities(true),
    });
    renderRoute('/workflows', '/workflows', <WorkflowsListPage />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not load workflows.');
    await user.click(within(alert).getByRole('button', { name: 'Retry' }));

    expect(await screen.findByText('Douyin Video Repurpose')).toBeInTheDocument();
    expect(screen.getByText('Echo Demo (all steps succeed)')).toBeInTheDocument();
    expect(screen.getByText('Failing Demo (deliberate)')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(workflowCalls).toBe(2);
  });

  it('shows the server detail in the inline error when the request fails with one', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ME },
      'GET /api/v1/workflows': {
        status: 503,
        body: { status: 503, detail: 'Workflows are paused for maintenance.' },
      },
      'GET /api/v1/capabilities': capabilities(true),
    });
    renderRoute('/workflows', '/workflows', <WorkflowsListPage />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Workflows are paused for maintenance.');
    expect(alert).not.toHaveTextContent('Could not load workflows.');
    expect(within(alert).getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('says nothing matches a search and clears it with one action', async () => {
    const user = userEvent.setup();
    stubAndRender([DOUYIN, ECHO, FAIL]);

    await screen.findByText('Echo Demo (all steps succeed)');
    const search = screen.getByPlaceholderText('Search workflows...');
    await user.type(search, 'zzz');

    expect(screen.getByText('No workflow matches your search.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Clear filters' }));

    expect(search).toHaveValue('');
    expect(screen.getByText('Douyin Video Repurpose')).toBeInTheDocument();
    expect(screen.getByText('Echo Demo (all steps succeed)')).toBeInTheDocument();
    expect(screen.getByText('Failing Demo (deliberate)')).toBeInTheDocument();
  });

  it('points an account with no workflows at the guide', async () => {
    stubAndRender([]);

    expect(await screen.findByText('There are no workflows yet.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Read the guide' })).toHaveAttribute('href', '/docs/guides');
    expect(screen.queryByRole('button', { name: 'Clear filters' })).not.toBeInTheDocument();
  });

  it('paints each owner avatar from a tone token, not an inline colour', async () => {
    const { container } = stubAndRender([DOUYIN, ECHO]);

    await screen.findByText('Echo Demo (all steps succeed)');
    const avatars = container.querySelectorAll<HTMLElement>('.wf-list__owner-avatar');
    expect(avatars.length).toBeGreaterThan(0);
    for (const avatar of avatars) {
      expect(avatar.dataset.tone).toMatch(/^(blue|cyan|violet|amber|green|rose)$/);
      expect(avatar).not.toHaveAttribute('style');
    }
  });

  describe('in Vietnamese', () => {
    beforeEach(async () => {
      await i18n.changeLanguage('vi');
    });
    afterEach(async () => {
      await i18n.changeLanguage('en');
    });

    it('renders the heading, the table label and every column header', async () => {
      stubApi({
        'GET /api/v1/auth/me': { status: 200, body: ME },
        'GET /api/v1/workflows': { status: 200, body: [DOUYIN, ECHO] },
        'GET /api/v1/capabilities': capabilities(true),
      });
      renderRoute('/workflows', '/workflows', <WorkflowsListPage />);

      expect(await screen.findByRole('heading', { name: 'Quy trình' })).toBeInTheDocument();
      const table = await screen.findByRole('region', { name: 'Bảng quy trình' });
      for (const column of ['Tên', 'Trạng thái', 'Chủ sở hữu', 'Số bước', 'Thao tác']) {
        expect(withinTable(table, column)).toBeInTheDocument();
      }
      expect(screen.getByText('Đang hiển thị 2 trên 2 quy trình')).toBeInTheDocument();
    });
  });
});

function stubAndRender(workflows: WorkflowResponse[]) {
  stubApi({
    'GET /api/v1/auth/me': { status: 200, body: ME },
    'GET /api/v1/workflows': { status: 200, body: workflows },
    'GET /api/v1/capabilities': capabilities(true),
  });
  return renderRoute('/workflows', '/workflows', <WorkflowsListPage />);
}

function withinTable(scope: HTMLElement, text: string): HTMLElement {
  const node = [...scope.querySelectorAll('th')].find((th) => th.textContent?.trim() === text);
  if (!node) throw new Error(`column ${text} not found`);
  return node as HTMLElement;
}
