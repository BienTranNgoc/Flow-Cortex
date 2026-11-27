import { setToken, type RunResponse } from '@flow-cortex/api-client';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import i18n from '../../lib/i18n';
import viWorkflow from '../../locales/vi/workflow.json';
import { renderRoute, settled, stubApi, type Handler } from '../../test/fakeApi';
import { stubModalDialog } from '../../test/modalDialog';
import { RunProgressPage } from './RunProgressPage';

// The run's push stream is not under test; the page only needs the hook to exist.
vi.mock('./useRunEvents', () => ({ useRunEvents: () => undefined }));

const RUN = 'GET /api/v1/runs/r1';
const FEEDBACK = 'PUT /api/v1/runs/r1/feedback';
const STORED = {
  verdict: 'down',
  issue: 'low_quality',
  detail: 'Too short',
  updatedAt: '2026-11-27T10:00:00Z',
};

function completedRun(overrides: Partial<RunResponse> = {}): RunResponse {
  return {
    id: 'r1',
    workflowId: 'w1',
    status: 'completed',
    input: 'weekly numbers',
    steps: [
      {
        id: 's1',
        stepOrder: 1,
        type: 'echo',
        mode: 'inline',
        status: 'completed',
        attempt: 1,
        deliveryCount: 0,
        output: 'Hello world',
        outputs: [],
        children: [],
        editable: false,
        editableFields: [],
        editRevision: 1,
      },
    ],
    insightPending: false,
    ...overrides,
  };
}

function renderRun(run: RunResponse, extra: Record<string, Handler> = {}) {
  const calls = stubApi({
    [RUN]: { status: 200, body: run },
    'GET /api/v1/runs/r1/inputs': { status: 200, body: [] },
    ...extra,
  });
  const { queryClient } = renderRoute(
    '/workflows/:workflowId/runs/:runId',
    '/workflows/w1/runs/r1',
    <main>
      <RunProgressPage />
    </main>,
  );
  return { calls, queryClient };
}

function runReads(calls: Array<{ method: string; path: string }>) {
  return calls.filter((call) => call.method === 'GET' && call.path === '/api/v1/runs/r1').length;
}

async function openDown() {
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: 'Not good' }));
  const dialog = await screen.findByRole('dialog', { name: 'What went wrong?' });
  return { user, dialog };
}

describe('run result section', () => {
  let restoreDialog: () => void;
  beforeEach(() => {
    setToken('t', true);
    restoreDialog = stubModalDialog();
    Object.assign(Element.prototype, { scrollIntoView: vi.fn() });
  });
  afterEach(() => {
    restoreDialog();
    Reflect.deleteProperty(Element.prototype, 'scrollIntoView');
    setToken(null);
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('shows the insight card above the eval bar, with its highlights as a list', async () => {
    renderRun(
      completedRun({
        insight: { headline: 'Hanoi leads', highlights: ['Sales 120', 'Hue trails'] },
      }),
    );

    const card = await screen.findByRole('region', { name: 'Summary of this result' });
    const bar = screen.getByRole('group', { name: 'How is this result?' });

    expect(card).toHaveTextContent('Hanoi leads');
    expect(
      within(card)
        .getAllByRole('listitem')
        .map((item) => item.textContent),
    ).toEqual(['Sales 120', 'Hue trails']);
    expect(card.compareDocumentPosition(bar) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('shows no card for a run without an insight', async () => {
    const { queryClient } = renderRun(completedRun());
    await screen.findByRole('group', { name: 'How is this result?' });
    expect(
      screen.queryByRole('region', { name: 'Summary of this result' }),
    ).not.toBeInTheDocument();
    await settled(queryClient);
  });

  it('keeps the eval bar away from a run that has not completed', async () => {
    const { queryClient } = renderRun(completedRun({ status: 'running' }));
    await screen.findByRole('heading', { level: 1 });
    await settled(queryClient);
    expect(screen.queryByRole('group', { name: 'How is this result?' })).not.toBeInTheDocument();
  });

  it('needs an issue before a thumbs-down can be sent', async () => {
    renderRun(completedRun());

    const { user, dialog } = await openDown();
    const send = within(dialog).getByRole('button', { name: 'Send' });

    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(dialog).toHaveTextContent('Your feedback goes to the Nodi team.');
    expect(send).toBeDisabled();
    await user.selectOptions(within(dialog).getByLabelText('Issue'), 'low_quality');
    expect(send).toBeEnabled();
  });

  it('sends a thumbs-down with its issue and detail, then shows the sent state with "Change"', async () => {
    const { calls } = renderRun(completedRun(), { [FEEDBACK]: { status: 200, body: STORED } });

    const { user, dialog } = await openDown();
    await user.selectOptions(within(dialog).getByLabelText('Issue'), 'low_quality');
    await user.type(within(dialog).getByLabelText('Details (optional)'), 'Too short');
    await user.click(within(dialog).getByRole('button', { name: 'Send' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(calls.find((call) => call.method === 'PUT')?.body).toEqual({
      verdict: 'down',
      issue: 'low_quality',
      detail: 'Too short',
    });
    expect(screen.getByText(/You rated this result not good/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Change' })).toHaveFocus();
    expect(screen.queryByRole('button', { name: 'Not good' })).not.toBeInTheDocument();
  });

  it('sends a thumbs-up with no issue field', async () => {
    const { calls } = renderRun(completedRun(), {
      [FEEDBACK]: { status: 200, body: { verdict: 'up', updatedAt: STORED.updatedAt } },
    });
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Good' }));
    const dialog = await screen.findByRole('dialog', { name: 'What worked well?' });
    expect(within(dialog).queryByLabelText('Issue')).not.toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Send' }));

    await waitFor(() => expect(screen.getByRole('button', { name: 'Change' })).toBeInTheDocument());
    expect(calls.find((call) => call.method === 'PUT')?.body).toEqual({ verdict: 'up' });
  });

  it('shows the stored feedback as sent on load, and "Change" brings the thumbs back', async () => {
    renderRun(completedRun({ feedback: STORED }));
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Change' }));

    expect(screen.getByRole('button', { name: 'Good' })).toHaveFocus();
    expect(screen.getByRole('button', { name: 'Not good' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Not good' }));
    const dialog = await screen.findByRole('dialog', { name: 'What went wrong?' });
    expect(within(dialog).getByLabelText('Issue')).toHaveValue('low_quality');
    expect(within(dialog).getByLabelText('Details (optional)')).toHaveValue('Too short');
  });

  it('keeps the dialog open with the entered values and an error when the send fails', async () => {
    renderRun(completedRun(), {
      [FEEDBACK]: () => {
        throw new TypeError('network down');
      },
    });

    const { user, dialog } = await openDown();
    await user.selectOptions(within(dialog).getByLabelText('Issue'), 'off_brand');
    await user.type(within(dialog).getByLabelText('Details (optional)'), 'Wrong colours');
    await user.click(within(dialog).getByRole('button', { name: 'Send' }));

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      "Couldn't send your feedback",
    );
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(within(dialog).getByLabelText('Issue')).toHaveValue('off_brand');
    expect(within(dialog).getByLabelText('Details (optional)')).toHaveValue('Wrong colours');
    expect(within(dialog).getByRole('button', { name: 'Send' })).toBeEnabled();
  });

  it('closes on Escape and on Cancel and gives focus back to the thumb', async () => {
    renderRun(completedRun());
    const { user } = await openDown();

    await user.keyboard('{Escape}');

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Not good' })).toHaveFocus();

    await user.click(screen.getByRole('button', { name: 'Not good' }));
    await user.click(await screen.findByRole('button', { name: 'Cancel' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Not good' })).toHaveFocus();
  });

  it('speaks Vietnamese', async () => {
    await i18n.changeLanguage('vi');
    try {
      const { queryClient } = renderRun(completedRun());
      const user = userEvent.setup();

      await user.click(await screen.findByRole('button', { name: viWorkflow.eval.down }));

      const dialog = await screen.findByRole('dialog', { name: viWorkflow.eval.dialog.titleDown });
      expect(dialog).toHaveTextContent(viWorkflow.eval.dialog.note);
      await settled(queryClient);
    } finally {
      await i18n.changeLanguage('en');
    }
  });

  describe('insight written after the run completed', () => {
    it('re-reads the run every 4 seconds while pending and stops once the insight arrives', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true });
      let reads = 0;
      const insight = { headline: 'Hanoi leads', highlights: ['Sales 120'] };
      const { calls, queryClient } = renderRun(completedRun({ insightPending: true }), {
        [RUN]: () => {
          reads += 1;
          return {
            status: 200,
            body:
              reads < 3
                ? completedRun({ insightPending: true })
                : completedRun({ insight, insightPending: false }),
          };
        },
      });
      await screen.findByRole('group', { name: 'How is this result?' });
      expect(runReads(calls)).toBe(1);

      await act(() => vi.advanceTimersByTimeAsync(3_900));
      expect(runReads(calls)).toBe(1);
      await act(() => vi.advanceTimersByTimeAsync(200));
      expect(runReads(calls)).toBe(2);
      expect(
        screen.queryByRole('region', { name: 'Summary of this result' }),
      ).not.toBeInTheDocument();

      await act(() => vi.advanceTimersByTimeAsync(4_000));
      expect(
        await screen.findByRole('region', { name: 'Summary of this result' }),
      ).toHaveTextContent('Hanoi leads');
      await settled(queryClient);
      const afterInsight = runReads(calls);
      await act(() => vi.advanceTimersByTimeAsync(20_000));
      expect(runReads(calls)).toBe(afterInsight);
    });

    it('does not poll a run whose insight is not pending', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true });
      const { calls, queryClient } = renderRun(completedRun());
      await screen.findByRole('group', { name: 'How is this result?' });
      await settled(queryClient);

      await act(() => vi.advanceTimersByTimeAsync(20_000));

      expect(runReads(calls)).toBe(1);
    });
  });
});
