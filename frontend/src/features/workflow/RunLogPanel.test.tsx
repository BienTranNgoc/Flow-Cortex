import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, stubApi } from '../../test/fakeApi';
import { RunLogPanel } from './RunLogPanel';

const LOG_PATH = '/api/v1/runs/r1/logs';
const TRACE = '4bf92f3577b34da6a3ce929d0e0e4736';

const FAILED_LOG = {
  status: 'error',
  traceId: TRACE,
  stepCount: 3,
  summary: { errorCount: 1, warningCount: 0, firstError: 'Worker timed out' },
  entries: [
    {
      stepOrder: 2,
      type: 'extract_audio',
      level: 'error',
      status: 'error',
      message: 'Worker timed out',
      errorCode: 'media.timeout',
      attempt: 1,
      deliveryCount: 1,
      startedAt: '2026-10-01T10:00:00Z',
      finishedAt: '2026-10-01T10:00:05Z',
      durationMs: 5000,
    },
    {
      stepOrder: 1,
      type: 'probe',
      level: 'info',
      status: 'completed',
      attempt: 1,
      deliveryCount: 1,
    },
  ],
};

function renderPanel() {
  return renderRoute('/', '/', <RunLogPanel runId="r1" />);
}

describe('RunLogPanel', () => {
  it('copies the trace id and announces it', async () => {
    const user = userEvent.setup();
    stubApi({ [`GET ${LOG_PATH}`]: { status: 200, body: FAILED_LOG } });
    renderPanel();
    await user.click(screen.getByRole('button', { name: 'Run log' }));

    await user.click(await screen.findByRole('button', { name: 'Copy trace id' }));

    expect(await navigator.clipboard.readText()).toBe(TRACE);
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Copied'));
  });

  it('asks for the level the member picks', async () => {
    const user = userEvent.setup();
    const calls = stubApi({
      [`GET ${LOG_PATH}`]: { status: 200, body: { ...FAILED_LOG, entries: [] } },
    });
    renderPanel();
    await user.click(screen.getByRole('button', { name: 'Run log' }));
    await screen.findByText('No log entries at this level.');

    await user.click(screen.getByRole('button', { name: 'Errors' }));

    await waitFor(() => expect(calls).toHaveLength(2));
  });
});
