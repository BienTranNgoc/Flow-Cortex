import { setToken, type AdminRunResponse } from '@flow-cortex/api-client';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ADMIN_ME, renderRoute, staffMe, stubApi } from '../../test/fakeApi';
import { RunsPage } from './RunsPage';

const RUNS_PATH = 'GET /api/v1/admin/runs';
const ME_PATH = 'GET /api/v1/auth/me';
const WORKFLOW = '3f2b8c1e-5a4d-4e6f-9a7b-1c2d3e4f5a6b';

function run(overrides: Partial<AdminRunResponse> = {}): AdminRunResponse {
  return {
    id: 'run-1',
    workflowId: WORKFLOW,
    ownerId: 'u-1',
    workflowName: 'Resize video',
    ownerEmail: 'mai@flowcortex.dev',
    status: 'error',
    startedAt: '2026-11-02T10:00:00Z',
    ...overrides,
  };
}

const list = { status: 200, body: { items: [run()], page: 0, size: 20, total: 1 } };

beforeEach(() => setToken('admin-token-runs-filters', true));
afterEach(() => {
  setToken(null);
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('RunsPage filters', () => {
  it('applies the filters the address carries and shows them', async () => {
    const calls = stubApi({ [RUNS_PATH]: list });
    renderRoute(
      '/runs',
      `/runs?status=error&workflowId=${WORKFLOW}&from=2026-11-02&to=2026-11-03`,
      <RunsPage />,
    );

    await screen.findByRole('table');
    const query = calls.find((call) => call.path === '/api/v1/admin/runs')?.query;
    expect(query?.get('status')).toBe('error');
    expect(query?.get('workflowId')).toBe(WORKFLOW);
    expect(query?.get('from')).toBe('2026-11-01T17:00:00Z');
    expect(query?.get('to')).toBe('2026-11-03T17:00:00Z');
  });

  it('sends day edges of the chosen dates and resets to the first page', async () => {
    const calls = stubApi({ [RUNS_PATH]: list });
    renderRoute('/runs', '/runs', <RunsPage />);
    await screen.findByRole('table');

    fireEvent.change(screen.getByLabelText('From date'), { target: { value: '2026-11-02' } });
    await waitFor(() =>
      expect(calls.at(-1)?.query.get('from')).toBe('2026-11-01T17:00:00Z'),
    );
    expect(calls.at(-1)?.query.get('page')).toBe('0');
    expect(calls.at(-1)?.query.has('to')).toBe(false);
  });

  it('refuses a reversed range instead of asking the server', async () => {
    const calls = stubApi({ [RUNS_PATH]: list });
    renderRoute('/runs', '/runs?from=2026-11-03&to=2026-11-02', <RunsPage />);

    expect(await screen.findByRole('alert')).toHaveTextContent('The start date must not be after the end date.');
    expect(calls.filter((call) => call.path === '/api/v1/admin/runs')).toHaveLength(0);
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('ignores filter values that cannot be valid', async () => {
    const calls = stubApi({ [RUNS_PATH]: list });
    renderRoute('/runs', '/runs?status=paused&workflowId=abc&from=nope', <RunsPage />);

    await screen.findByRole('table');
    const query = calls.find((call) => call.path === '/api/v1/admin/runs')?.query;
    expect(['status', 'workflowId', 'from'].some((key) => query?.has(key))).toBe(false);
  });
});

describe('RunsPage export', () => {
  function stubExport(total: string) {
    URL.createObjectURL = vi.fn(() => 'blob:csv');
    URL.revokeObjectURL = vi.fn();
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    const calls = stubApi({ [RUNS_PATH]: list, [ME_PATH]: { status: 200, body: ADMIN_ME } });
    const routed = globalThis.fetch;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: string, init?: RequestInit) =>
        input.startsWith('/api/v1/admin/runs/export')
          ? new Response('a\r\n', {
              status: 200,
              headers: { 'Content-Disposition': 'attachment; filename="runs-20261108.csv"', 'X-Total-Count': total },
            })
          : routed(input, init),
      ),
    );
    return calls;
  }

  it('offers the export only to a holder of the export key', async () => {
    stubApi({
      [RUNS_PATH]: list,
      [ME_PATH]: { status: 200, body: staffMe('CUSTOMER_SUPPORT', ['workflow.run.view']) },
    });
    const { queryClient } = renderRoute('/runs', '/runs', <RunsPage />);
    await screen.findByRole('table');
    await waitFor(() => expect(queryClient.isFetching()).toBe(0));

    expect(screen.queryByRole('button', { name: 'Export CSV' })).not.toBeInTheDocument();
  });

  it('exports with the current filters and says when the file holds only the newest 1000', async () => {
    stubExport('1500');
    renderRoute('/runs', '/runs?status=error', <RunsPage />);

    await userEvent.click(await screen.findByRole('button', { name: 'Export CSV' }));

    expect(await screen.findByRole('status')).toHaveTextContent('The file holds the newest 1,000 of 1,500 runs.');
    const exported = vi.mocked(globalThis.fetch).mock.calls.find(([url]) => String(url).includes('/runs/export'));
    expect(String(exported?.[0])).toContain('status=error');
  });
});
