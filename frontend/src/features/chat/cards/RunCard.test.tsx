import { screen } from '@testing-library/react';
import { renderRoute, settled, stubApi } from '../../../test/fakeApi';
import { RunCard } from './RunCard';

const RUN = (status: string, extra: Record<string, unknown> = {}) => ({
  status: 200,
  body: {
    id: 'r1',
    status,
    steps: [
      { id: 's1', stepOrder: 1, type: 'probe', status: 'completed', attempt: 1, deliveryCount: 1, outputs: [], mode: 'inline' },
      { id: 's2', stepOrder: 2, type: 'APPROVAL_CHECKPOINT', status: 'pending', attempt: 1, deliveryCount: 0, outputs: [], mode: 'inline' },
    ],
    ...extra,
  },
});

function renderRun() {
  return renderRoute('/chat/:id', '/chat/c1', <RunCard runId="r1" />);
}

describe('RunCard', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('shows the status and step progress and links to the run page for review when it waits for approval', async () => {
    stubApi({ 'GET /api/v1/runs/r1': RUN('waiting_approval') });
    const { queryClient } = renderRun();
    await settled(queryClient);

    expect(await screen.findByText('Waiting for your review')).toBeInTheDocument();
    expect(screen.getByText('1 of 2 steps done')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Review needed' })).toHaveAttribute('href', '/runs/r1');
  });

  it('offers the outputs of a completed run and no review link', async () => {
    stubApi({
      'GET /api/v1/runs/r1': RUN('completed', {
        steps: [
          {
            id: 's1',
            stepOrder: 1,
            type: 'render',
            status: 'completed',
            attempt: 1,
            deliveryCount: 1,
            outputs: [{ name: 'ad-video.mp4', mime: 'video/mp4', bytes: 10 }],
            mode: 'remote',
          },
        ],
      }),
    });
    const { queryClient } = renderRun();
    await settled(queryClient);

    expect(await screen.findByText('ad-video.mp4')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Review needed' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Open results' })).toHaveAttribute('href', '/runs/r1');
  });
});
