import { setToken } from '@flow-cortex/api-client';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, settled, stubApi } from '../../test/fakeApi';
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

/** jsdom has no `showModal`/`close`; these set and clear `open` and fire `close` like a browser. */
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
        if (!this.hasAttribute('open')) return;
        this.removeAttribute('open');
        this.dispatchEvent(new Event('close'));
      },
    },
  });
  return () => {
    delete proto.showModal;
    delete proto.close;
  };
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
});
