import { setToken, type AdminWorkflowResponse } from '@flow-cortex/api-client';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ADMIN_ME, renderRoute, staffMe, stubApi } from '../../test/fakeApi';
import { WorkflowsPage } from './WorkflowsPage';

const LIST_PATH = 'GET /api/v1/admin/workflows';
const ME_PATH = 'GET /api/v1/auth/me';

function workflow(overrides: Partial<AdminWorkflowResponse> = {}): AdminWorkflowResponse {
  return {
    id: '3f2b8c1e-5a4d-4e6f-9a7b-1c2d3e4f5a6b',
    name: 'Resize video',
    ownerId: 'u-1',
    ownerEmail: 'mai@flowcortex.dev',
    status: 'published',
    shared: true,
    steps: 3,
    runs: 1200,
    runsLast7Days: 14,
    lastRunAt: '2026-11-07T09:00:00Z',
    createdAt: '2026-10-01T10:00:00Z',
    updatedAt: '2026-11-01T10:00:00Z',
    ...overrides,
  };
}

function page(items: AdminWorkflowResponse[]) {
  return { status: 200, body: { items, page: 0, size: 20, total: items.length } };
}

beforeEach(() => setToken('admin-token-workflows', true));
afterEach(() => {
  setToken(null);
  vi.unstubAllGlobals();
});

describe('WorkflowsPage', () => {
  it('filters by status and by name, and clears both', async () => {
    const calls = stubApi({ [LIST_PATH]: page([workflow()]) });
    renderRoute('/workflows', '/workflows', <WorkflowsPage />);
    await screen.findByRole('table');

    await userEvent.selectOptions(screen.getByLabelText('Status'), 'draft');
    await waitFor(() => expect(calls.at(-1)?.query.get('status')).toBe('draft'));

    await userEvent.type(screen.getByLabelText('Name'), '  50% off ');
    await userEvent.click(screen.getByRole('button', { name: 'Search' }));
    await waitFor(() => expect(calls.at(-1)?.query.get('q')).toBe('50% off'));
    expect(calls.at(-1)?.query.get('page')).toBe('0');

    await userEvent.click(screen.getByRole('button', { name: 'Clear filters' }));
    await waitFor(() => expect(calls.at(-1)?.query.has('q')).toBe(false));
    expect(calls.at(-1)?.query.has('status')).toBe(false);
    expect(screen.getByLabelText('Name')).toHaveValue('');
  });

  it('offers the export only with the export key', async () => {
    stubApi({
      [LIST_PATH]: page([workflow()]),
      [ME_PATH]: { status: 200, body: staffMe('CUSTOMER_SUPPORT', ['workflow.workflow.view']) },
    });
    const viewOnly = renderRoute('/workflows', '/workflows', <WorkflowsPage />);
    await screen.findByRole('table');
    await waitFor(() => expect(viewOnly.queryClient.isFetching()).toBe(0));
    expect(screen.queryByRole('button', { name: 'Export CSV' })).not.toBeInTheDocument();
    viewOnly.unmount();

    stubApi({ [LIST_PATH]: page([workflow()]), [ME_PATH]: { status: 200, body: ADMIN_ME } });
    renderRoute('/workflows', '/workflows', <WorkflowsPage />);
    expect(await screen.findByRole('button', { name: 'Export CSV' })).toBeInTheDocument();
  });
});
