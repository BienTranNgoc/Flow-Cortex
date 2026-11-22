import { setToken } from '@flow-cortex/api-client';
import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, settled, stubApi } from '../../test/fakeApi';
import { stubModalDialog } from '../../test/modalDialog';
import { RunHistoryPage } from './RunHistoryPage';

const WORKFLOW_ID = '3f2b8c1e-5a4d-4e7b-9c10-0a1b2c3d4e5f';

const COMPLETED = {
  id: 'a1',
  workflowId: WORKFLOW_ID,
  workflowName: 'Dub',
  title: 'Launch cut',
  status: 'completed',
  favorite: false,
  startedAt: '2026-11-02T10:00:00Z',
  finishedAt: '2026-11-02T10:02:30Z',
  durationSeconds: 150,
};

const SUMMARY = {
  days: 30,
  from: '2026-10-10',
  to: '2026-11-08',
  runs: 2,
  failed: 0,
  failureRate: 0,
  series: [{ day: '2026-11-08', runs: 2, failed: 0 }],
  topWorkflows: [{ workflowId: WORKFLOW_ID, name: 'Dub', runs: 2 }],
};

function page(items: unknown[], total = items.length) {
  return { status: 200, body: { items, page: 0, size: 20, total } };
}

function stubHistory(items: unknown[], extra: Parameters<typeof stubApi>[0] = {}) {
  return stubApi({
    'GET /api/v1/runs': page(items),
    'GET /api/v1/runs/workflows': { status: 200, body: [{ id: WORKFLOW_ID, name: 'Dub' }] },
    'GET /api/v1/runs/usage-summary': { status: 200, body: SUMMARY },
    ...extra,
  });
}

function renderPage(url = '/runs') {
  return renderRoute('/runs', url, <RunHistoryPage />);
}

describe('RunHistoryPage', () => {
  let restoreDialog: () => void;
  beforeEach(() => {
    setToken('t', true);
    restoreDialog = stubModalDialog();
  });
  afterEach(() => {
    restoreDialog();
    setToken(null);
    vi.unstubAllGlobals();
  });

  it('keeps the run when the delete dialog is cancelled and removes it when confirmed', async () => {
    let live = [COMPLETED];
    const calls = stubHistory([], {
      'GET /api/v1/runs': () => page(live),
      'DELETE /api/v1/runs/a1': () => {
        live = [];
        return { status: 204 };
      },
    });
    const user = userEvent.setup();
    const { queryClient } = renderPage();
    await screen.findByRole('link', { name: 'Open run Launch cut' });

    await user.click(screen.getByRole('button', { name: 'Actions for Launch cut' }));
    await user.click(screen.getByRole('menuitem', { name: 'Delete' }));
    await user.click(await screen.findByRole('button', { name: 'Keep run', hidden: true }));
    expect(calls.some((call) => call.method === 'DELETE')).toBe(false);
    expect(screen.getByRole('link', { name: 'Open run Launch cut' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Actions for Launch cut' }));
    await user.click(screen.getByRole('menuitem', { name: 'Delete' }));
    await user.click(await screen.findByRole('button', { name: 'Delete run', hidden: true }));

    await waitFor(() =>
      expect(screen.queryByRole('link', { name: 'Open run Launch cut' })).not.toBeInTheDocument(),
    );
    await settled(queryClient);
    expect(screen.getByText('You have no runs yet. Start one from a workflow.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Go to Workflows' })).toHaveAttribute('href', '/workflows');
  });

  it('sends the filters from the address, with local dates as Ho Chi Minh day bounds', async () => {
    const calls = stubHistory([COMPLETED]);
    renderPage(`/runs?status=error&workflowId=${WORKFLOW_ID}&from=2026-11-02&to=2026-11-03&favorite=1`);
    await screen.findByRole('link', { name: 'Open run Launch cut' });

    const listCall = calls.find((call) => call.path === '/api/v1/runs');
    expect(listCall).toBeDefined();
    const sent = vi.mocked(fetch).mock.calls.map(([input]) => String(input));
    const url = new URL(sent.find((input) => input.startsWith('/api/v1/runs?')) ?? '', 'http://x');
    expect(Object.fromEntries(url.searchParams)).toMatchObject({
      status: 'error',
      workflowId: WORKFLOW_ID,
      from: '2026-11-01T17:00:00Z',
      to: '2026-11-03T17:00:00Z',
      favorite: 'true',
    });
    expect(screen.getByLabelText('Status')).toHaveValue('error');
    expect(screen.getByRole('button', { name: 'Starred only' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('switches the usage window and refetches', async () => {
    const calls = stubHistory([COMPLETED]);
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('link', { name: 'Open run Launch cut' });

    await user.click(screen.getByRole('button', { name: '7 days' }));
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '7 days' })).toHaveAttribute('aria-pressed', 'true'),
    );
    expect(calls.filter((call) => call.path === '/api/v1/runs/usage-summary').length).toBeGreaterThan(1);
    expect(screen.getByRole('img', { name: 'Runs per day' })).toBeInTheDocument();
  });

  describe('result summary', () => {
    const RESULTS = 'GET /api/v1/assets/run-results';
    const summary = (patch: Record<string, unknown>) => ({
      status: 200,
      body: {
        results: [
          { runId: 'a1', count: 0, kinds: [], thumbnails: [], registration: 'none', ...patch },
        ],
      },
    });

    it('shows the count and the thumbnails of a run that produced files', async () => {
      stubHistory([COMPLETED], {
        [RESULTS]: summary({
          count: 3,
          registration: 'done',
          thumbnails: [{ url: 'https://files/a.png' }, { url: 'https://files/b.png' }],
        }),
      });
      const { container } = renderPage();
      expect(await screen.findByText('3 files')).toBeInTheDocument();
      const images = Array.from(container.querySelectorAll('.run-row img'));
      expect(images.map((image) => image.getAttribute('src'))).toEqual([
        'https://files/a.png',
        'https://files/b.png',
      ]);
      expect(images.every((image) => image.getAttribute('alt') === '')).toBe(true);
    });

    it('shows "Saving files…" and no count while the registration is pending', async () => {
      stubHistory([COMPLETED], { [RESULTS]: summary({ registration: 'pending' }) });
      renderPage();
      expect(await screen.findByText('Saving files…')).toBeInTheDocument();
      expect(screen.queryByText(/\d+ files?$/)).not.toBeInTheDocument();
    });

    it('turns "Saving files…" into the count without a reload', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true });
      try {
        let registered = false;
        const calls = stubHistory([COMPLETED], {
          [RESULTS]: () =>
            registered
              ? summary({ count: 2, registration: 'done' })
              : summary({ registration: 'pending' }),
        });
        renderPage();
        await screen.findByText('Saving files…');

        registered = true;
        await act(() => vi.advanceTimersByTimeAsync(5_000));
        expect(await screen.findByText('2 files')).toBeInTheDocument();
        expect(screen.queryByText('Saving files…')).not.toBeInTheDocument();
        expect(calls.filter((call) => call.path === '/api/v1/assets/run-results')).toHaveLength(2);
      } finally {
        vi.useRealTimers();
      }
    });

    it("polls only the pending run and keeps a done row's thumbnail URL across polls", async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true });
      try {
        const second = { ...COMPLETED, id: 'a2', title: 'Second cut' };
        const requested: string[][] = [];
        let signatures = 0;
        let registered = false;
        stubHistory([COMPLETED, second], {
          [RESULTS]: () => {
            const url = String(vi.mocked(fetch).mock.calls.at(-1)?.[0]);
            const ids = new URLSearchParams(url.split('?')[1]).getAll('runId');
            requested.push(ids);
            // Each request signs afresh, as the object store does: a run asked about again gets a new URL.
            signatures += 1;
            return {
              status: 200,
              body: {
                results: ids.map((runId) =>
                  runId === 'a2' && !registered
                    ? { runId, count: 0, kinds: [], thumbnails: [], registration: 'pending' }
                    : {
                        runId,
                        count: 1,
                        kinds: ['image'],
                        thumbnails: [{ url: `https://files/${runId}.png?sig=${signatures}` }],
                        registration: 'done',
                      },
                ),
              },
            };
          },
        });
        const { container } = renderPage();
        await screen.findByText('Saving files…');
        const sources = () =>
          Array.from(container.querySelectorAll('.run-row img')).map((image) =>
            image.getAttribute('src'),
          );
        expect(sources()).toEqual(['https://files/a1.png?sig=1']);

        await act(() => vi.advanceTimersByTimeAsync(5_000));
        expect(requested).toEqual([['a1', 'a2'], ['a2']]);
        expect(sources()).toEqual(['https://files/a1.png?sig=1']);

        registered = true;
        await act(() => vi.advanceTimersByTimeAsync(5_000));
        await waitFor(() => expect(screen.queryByText('Saving files…')).not.toBeInTheDocument());
        expect(requested).toEqual([['a1', 'a2'], ['a2'], ['a2']]);
        expect(sources()).toEqual(['https://files/a1.png?sig=1', 'https://files/a2.png?sig=3']);

        await act(() => vi.advanceTimersByTimeAsync(15_000));
        expect(requested).toHaveLength(3);
      } finally {
        vi.useRealTimers();
      }
    });

    it('leaves every row as it was when the summary request fails', async () => {
      stubHistory([COMPLETED], { [RESULTS]: { status: 503, body: { status: 503 } } });
      const { queryClient, container } = renderPage();
      await screen.findByRole('link', { name: 'Open run Launch cut' });
      await waitFor(
        () => expect(queryClient.getQueryState(['run-results', ['a1']])?.status).toBe('error'),
        { timeout: 4_000 },
      );

      expect(container.querySelector('.run-result')).toBeNull();
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
      expect(container.querySelector('.run-row__workflow')).toHaveTextContent('Dub');
    });
  });
});
