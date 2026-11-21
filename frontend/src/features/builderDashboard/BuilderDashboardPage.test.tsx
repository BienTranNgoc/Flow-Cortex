import { setToken } from '@flow-cortex/api-client';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, stubApi } from '../../test/fakeApi';
import { BuilderDashboardPage } from './BuilderDashboardPage';

const SUMMARY = '/api/v1/builder/dashboard/summary';
const CHART = '/api/v1/builder/dashboard/runs-chart';

function series(days: number) {
  return Array.from({ length: days }, (_, index) => ({
    day: `2026-11-${String(index + 1).padStart(2, '0')}`,
    runs: index === 0 ? 4 : 0,
    failed: index === 0 ? 1 : 0,
  }));
}

describe('BuilderDashboardPage', () => {
  beforeEach(() => setToken('t', false));
  afterEach(() => setToken(null));

  it('asks for the chosen period when the period is switched', async () => {
    stubApi({
      [`GET ${SUMMARY}`]: {
        status: 200,
        body: { totalWorkflows: 3, publishedWorkflows: 2, totalRuns: 4 },
      },
      [`GET ${CHART}`]: { status: 200, body: { period: '30d', series: series(30) } },
    });
    renderRoute('/builder/dashboard', '/builder/dashboard', <BuilderDashboardPage />);
    await screen.findByRole('img', { name: /4 runs over the last 30 days/i });

    stubApi({
      [`GET ${SUMMARY}`]: {
        status: 200,
        body: { totalWorkflows: 3, publishedWorkflows: 2, totalRuns: 4 },
      },
      [`GET ${CHART}`]: { status: 200, body: { period: '7d', series: series(7) } },
    });
    await userEvent.click(screen.getByRole('button', { name: '7 days' }));

    await screen.findByRole('img', { name: /over the last 7 days/i });
    const requested = vi.mocked(globalThis.fetch).mock.calls.map(([url]) => String(url));
    expect(requested.some((url) => url.startsWith(CHART) && url.includes('period=7d'))).toBe(true);
  });
});
