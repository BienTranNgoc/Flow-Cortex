import { setToken, type WorkflowReportResponse } from '@flow-cortex/api-client';
import { screen, waitFor } from '@testing-library/react';
import { renderRoute, staffMe, stubApi } from '../../test/fakeApi';
import { WorkflowReportPage } from './WorkflowReportPage';

const ID = '3f2b8c1e-5a4d-4e6f-9a7b-1c2d3e4f5a6b';
const REPORT_PATH = `GET /api/v1/admin/workflows/${ID}/report`;
const ME_PATH = 'GET /api/v1/auth/me';

function report(): WorkflowReportResponse {
  const points = Array.from({ length: 30 }, (_, index) => ({
    day: new Date(Date.UTC(2026, 9, 10 + index)).toISOString().slice(0, 10),
    runs: index === 29 ? 3 : 0,
    failed: index === 29 ? 1 : 0,
  }));
  return {
    workflow: {
      id: ID,
      name: 'Resize video',
      ownerId: 'u-1',
      ownerEmail: 'mai@flowcortex.dev',
      status: 'published',
      shared: true,
      steps: 3,
      runs: 128,
      runsLast7Days: 3,
      lastRunAt: '2026-11-08T05:00:00Z',
      createdAt: '2026-10-01T10:00:00Z',
      updatedAt: '2026-11-01T10:00:00Z',
    },
    countsByStatus: { completed: 120, error: 7, running: 1 },
    lastRunAt: '2026-11-08T05:00:00Z',
    series: points,
  };
}

beforeEach(() => setToken('admin-token-report', true));
afterEach(() => {
  setToken(null);
  vi.unstubAllGlobals();
});

describe('WorkflowReportPage', () => {
  it('hides "View runs" from a caller who cannot open the Runs page', async () => {
    stubApi({
      [ME_PATH]: { status: 200, body: staffMe('X', ['workflow.workflow.view']) },
      [REPORT_PATH]: { status: 200, body: report() },
    });
    const { queryClient } = renderRoute('/workflows/:workflowId', `/workflows/${ID}`, <WorkflowReportPage />);
    await screen.findByRole('heading', { level: 1, name: 'Resize video' });
    await waitFor(() => expect(queryClient.isFetching()).toBe(0));

    expect(screen.queryByRole('link', { name: 'View runs' })).not.toBeInTheDocument();
  });
});
