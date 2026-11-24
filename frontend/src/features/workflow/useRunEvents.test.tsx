import { render } from '@testing-library/react';
import { openEventStream, type EventStreamOptions, type RunStepResponse } from '@flow-cortex/api-client';
import { isLiveRunStatus, useRunEvents } from './useRunEvents';

const openMock = vi.fn();
const closeMock = vi.fn();

vi.mock('@flow-cortex/api-client', async (importOriginal) => ({
  ...(await importOriginal<object>()),
  openEventStream: (options: EventStreamOptions) => {
    openMock(options);
    return { close: closeMock };
  },
}));

function Probe({
  runId,
  status,
  steps,
}: Readonly<{
  runId: string;
  status: string;
  steps: readonly Pick<RunStepResponse, 'status'>[];
}>) {
  useRunEvents(runId, status, async () => undefined, steps);
  return null;
}

describe('useRunEvents & isLiveRunStatus', () => {
  beforeEach(() => {
    openMock.mockClear();
    closeMock.mockClear();
  });

  it('treats an errored run as live while any step is still running', () => {
    expect(isLiveRunStatus('error', [{ status: 'error' }, { status: 'running' }])).toBe(true);
    expect(isLiveRunStatus('error', [{ status: 'error' }, { status: 'completed' }])).toBe(false);
    expect(isLiveRunStatus('running', [])).toBe(true);
    expect(isLiveRunStatus('completed', [])).toBe(false);
    void openEventStream;
  });

  it('keeps stream open when run fails with running sibling and closes once settled', () => {
    const { rerender } = render(
      <Probe
        runId="r-1"
        status="error"
        steps={[{ status: 'running' }, { status: 'error' }]}
      />,
    );
    expect(openMock).toHaveBeenCalledTimes(1);
    expect(closeMock).not.toHaveBeenCalled();

    rerender(
      <Probe
        runId="r-1"
        status="error"
        steps={[{ status: 'error' }, { status: 'error' }]}
      />,
    );
    expect(closeMock).toHaveBeenCalledTimes(1);
  });
});
