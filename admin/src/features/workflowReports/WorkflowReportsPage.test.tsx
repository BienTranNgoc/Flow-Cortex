import { setToken, type WorkflowReportRowResponse } from '@flow-cortex/api-client';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, stubApi } from '../../test/fakeApi';
import { WorkflowReportsPage } from './WorkflowReportsPage';

const LIST_PATH = 'GET /api/v1/admin/workflow-reports';

function row(): WorkflowReportRowResponse {
  return {
    id: '3f2b8c1e-5a4d-4e6f-9a7b-1c2d3e4f5a6b',
    reason: 'scam',
    status: 'pending',
    createdAt: '2026-11-20T09:00:00Z',
    reporter: { id: 'u-2', email: 'lan@flowcortex.dev' },
    workflow: {
      id: 'w-1',
      name: 'Resize video',
      status: 'published',
      visibility: 'public',
      moderationStatus: 'visible',
      deleted: false,
      owner: { id: 'u-1', email: 'mai@flowcortex.dev' },
    },
  };
}

function page(items: WorkflowReportRowResponse[]) {
  return { status: 200, body: { items, page: 0, size: 20, total: items.length } };
}

beforeEach(() => setToken('admin-token-workflow-reports', true));
afterEach(() => {
  setToken(null);
  vi.unstubAllGlobals();
});

describe('WorkflowReportsPage', () => {
  it('lists a report with its reason, reporter and states, linking to the detail page', async () => {
    stubApi({ [LIST_PATH]: page([row()]) });
    renderRoute('/workflow-reports', '/workflow-reports', <WorkflowReportsPage />);

    const link = await screen.findByRole('link', { name: 'Resize video' });
    expect(link).toHaveAttribute('href', '/workflow-reports/3f2b8c1e-5a4d-4e6f-9a7b-1c2d3e4f5a6b');
    const table = within(screen.getByRole('table'));
    expect(table.getByText('Scam or fraud')).toBeInTheDocument();
    expect(table.getByText('lan@flowcortex.dev')).toBeInTheDocument();
    expect(table.getByText('Pending')).toBeInTheDocument();
    expect(table.getByText('Visible')).toBeInTheDocument();
  });

  it('shows an empty state instead of a table when no report matches', async () => {
    stubApi({ [LIST_PATH]: page([]) });
    renderRoute('/workflow-reports', '/workflow-reports', <WorkflowReportsPage />);

    expect(await screen.findByText('No reports yet.')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('filters by status and by name, and clears both', async () => {
    const calls = stubApi({ [LIST_PATH]: page([row()]) });
    renderRoute('/workflow-reports', '/workflow-reports', <WorkflowReportsPage />);
    await screen.findByRole('table');

    await userEvent.selectOptions(screen.getByLabelText('Status'), 'hidden');
    await waitFor(() => expect(calls.at(-1)?.query.get('status')).toBe('hidden'));

    await userEvent.type(screen.getByLabelText('Workflow name'), '  50% off ');
    await userEvent.click(screen.getByRole('button', { name: 'Search' }));
    await waitFor(() => expect(calls.at(-1)?.query.get('q')).toBe('50% off'));
    expect(calls.at(-1)?.query.get('page')).toBe('0');

    await userEvent.click(screen.getByRole('button', { name: 'Clear filters' }));
    await waitFor(() => expect(calls.at(-1)?.query.has('q')).toBe(false));
    expect(calls.at(-1)?.query.has('status')).toBe(false);
  });
});
