import { setToken } from '@flow-cortex/api-client';
import type { AdminRunResponse } from '@flow-cortex/api-client';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, settled, stubApi, type FakeReply } from '../../test/fakeApi';
import { RunsPage } from './RunsPage';

const RUNS_PATH = 'GET /api/v1/admin/runs';
// The API sends `null` for a value the row does not have; the generated types call it optional.
const WIRE_NULL = null as never;
const DISABLED: FakeReply = {
  status: 503,
  body: { status: 503, detail: 'Admin console is currently disabled' },
};

function run(overrides: Partial<AdminRunResponse> = {}): AdminRunResponse {
  return {
    id: 'run-1',
    workflowName: 'Resize video',
    ownerEmail: 'member1@flowcortex.dev',
    status: 'completed',
    startedAt: '2026-10-01T10:00:00Z',
    finishedAt: '2026-10-01T10:01:00Z',
    ...overrides,
  };
}

function page(items: AdminRunResponse[], total = items.length, pageNumber = 0) {
  return { status: 200, body: { items, page: pageNumber, size: 20, total } };
}

beforeEach(() => setToken('admin-token-runs'));
afterEach(() => {
  setToken(null);
  vi.unstubAllGlobals();
});

describe('RunsPage', () => {
  it('shows a loading status while the first page is on its way', () => {
    stubApi({ [RUNS_PATH]: () => new Promise<FakeReply>(() => {}) });
    renderRoute('/runs', '/runs', <RunsPage />);

    expect(screen.getByText('Loading runs…').tagName).toBe('OUTPUT');
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('shows an alert with a retry action when the list fails', async () => {
    const calls = stubApi({ [RUNS_PATH]: { status: 500, body: { status: 500, detail: 'boom' } } });
    renderRoute('/runs', '/runs', <RunsPage />);

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load runs.');
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(calls.filter((call) => call.path === '/api/v1/admin/runs')).toHaveLength(2));
  });

  it('lists every owner newest first as the server returns them, with engine errors as-is', async () => {
    const calls = stubApi({
      [RUNS_PATH]: page([
        run({ id: 'run-3', status: 'error', errorMessage: 'ffmpeg exited with status 1' }),
        run({ id: 'run-2', ownerEmail: 'suspended@flowcortex.dev', status: 'running', finishedAt: WIRE_NULL }),
        run({ id: 'run-1', ownerEmail: WIRE_NULL }),
      ]),
    });
    renderRoute('/runs', '/runs', <RunsPage />);

    const table = await screen.findByRole('table');
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(3);
    expect(rows[0]).toHaveTextContent('ffmpeg exited with status 1');
    expect(rows[1]).toHaveTextContent('suspended@flowcortex.dev');
    expect(rows[2]).toHaveTextContent('(deleted user)');
    expect(within(table).getAllByRole('columnheader').map((header) => header.textContent)).toEqual([
      'Workflow',
      'Owner',
      'Status',
      'Started',
      'Finished',
      'Error',
    ]);
    expect(calls[0]?.query.get('page')).toBe('0');
    expect(calls[0]?.query.get('size')).toBe('20');
    expect(calls[0]?.query.has('status')).toBe(false);
  });

  it('pages through the total with Previous and Next', async () => {
    const calls = stubApi({
      [RUNS_PATH]: () => {
        const requested = Number(calls.at(-1)?.query.get('page') ?? 0);
        return page([run({ id: `run-p${requested}` })], 45, requested);
      },
    });
    renderRoute('/runs', '/runs', <RunsPage />);

    expect(await screen.findByText('Page 1 of 3 · 45 runs')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Previous page' })).toHaveAttribute('aria-disabled', 'true');

    await userEvent.click(screen.getByRole('button', { name: 'Next page' }));
    expect(await screen.findByText('Page 2 of 3 · 45 runs')).toBeInTheDocument();
    expect(calls.at(-1)?.query.get('page')).toBe('1');
    expect(screen.getByRole('button', { name: 'Previous page' })).toHaveAttribute('aria-disabled', 'false');
  });

  it('marks the previous rows as busy while the next page loads', async () => {
    let release: (reply: FakeReply) => void = () => {};
    const calls = stubApi({
      [RUNS_PATH]: () =>
        calls.at(-1)?.query.get('page') === '1'
          ? new Promise<FakeReply>((resolve) => {
              release = resolve;
            })
          : page([run({ id: 'run-p0' })], 45),
    });
    renderRoute('/runs', '/runs', <RunsPage />);
    await screen.findByRole('table');

    await userEvent.click(screen.getByRole('button', { name: 'Next page' }));
    expect(await screen.findByText('Loading runs…')).toBeInTheDocument();
    expect(screen.getByRole('table').closest('.table-wrapper')).toHaveAttribute('aria-busy', 'true');

    release(page([run({ id: 'run-p1' })], 45, 1));
    expect(await screen.findByText('Page 2 of 3 · 45 runs')).toBeInTheDocument();
    expect(screen.queryByText('Loading runs…')).not.toBeInTheDocument();
    expect(screen.getByRole('table').closest('.table-wrapper')).toHaveAttribute('aria-busy', 'false');
  });

  it('offers the first page and keeps the pager when a later page comes back empty', async () => {
    const calls = stubApi({
      [RUNS_PATH]: () =>
        calls.at(-1)?.query.get('page') === '1' ? page([], 20, 1) : page([run()], 21),
    });
    renderRoute('/runs', '/runs', <RunsPage />);
    await screen.findByRole('table');

    await userEvent.click(screen.getByRole('button', { name: 'Next page' }));
    expect(await screen.findByText('No runs on this page.')).toBeInTheDocument();
    expect(screen.queryByText('No runs yet.')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Previous page' })).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Back to first page' }));
    expect(await screen.findByRole('table')).toBeInTheDocument();
    expect(calls.at(-1)?.query.get('page')).toBe('0');
  });

  it('names the page in the document title', async () => {
    stubApi({ [RUNS_PATH]: page([]) });
    renderRoute('/runs', '/runs', <RunsPage />);

    await waitFor(() => expect(document.title).toBe('Runs · Flow-Cortex Admin'));
  });

  it('filters by status and offers to clear a filter that matches nothing', async () => {
    const calls = stubApi({
      [RUNS_PATH]: () => (calls.at(-1)?.query.get('status') === 'error' ? page([]) : page([run()])),
    });
    renderRoute('/runs', '/runs', <RunsPage />);
    await screen.findByRole('table');

    await userEvent.selectOptions(screen.getByLabelText('Status'), 'error');
    expect(await screen.findByText('No runs match this filter')).toBeInTheDocument();
    expect(calls.at(-1)?.query.get('status')).toBe('error');
    expect(calls.at(-1)?.query.get('page')).toBe('0');

    await userEvent.click(screen.getByRole('button', { name: 'Clear filter' }));
    expect(await screen.findByRole('table')).toBeInTheDocument();
    expect(calls.at(-1)?.query.has('status')).toBe(false);
  });

  it('says so when there are no runs at all', async () => {
    stubApi({ [RUNS_PATH]: page([]) });
    renderRoute('/runs', '/runs', <RunsPage />);

    expect(await screen.findByText('No runs yet.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Clear filter' })).not.toBeInTheDocument();
  });

  it('shows the disabled banner and no table on a 503', async () => {
    stubApi({ [RUNS_PATH]: DISABLED });
    const { queryClient } = renderRoute('/runs', '/runs', <RunsPage />);

    expect(await screen.findByText('Admin console is disabled')).toBeInTheDocument();
    await settled(queryClient);
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('keeps the table it already showed, marked as earlier results, when the switch goes off', async () => {
    let disabled = false;
    stubApi({ [RUNS_PATH]: () => (disabled ? DISABLED : page([run()])) });
    renderRoute('/runs', '/runs', <RunsPage />);
    await screen.findByRole('table');

    disabled = true;
    await userEvent.click(screen.getByRole('button', { name: 'Refresh' }));

    expect(await screen.findByText('Admin console is disabled')).toBeInTheDocument();
    expect(screen.getByRole('table')).toBeInTheDocument();
    expect(screen.getByText('Showing the results loaded before this error.')).toBeInTheDocument();
  });
});
