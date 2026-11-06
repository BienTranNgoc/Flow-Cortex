import { setToken } from '@flow-cortex/api-client';
import { screen } from '@testing-library/react';
import i18n from '../../lib/i18n';
import viDashboard from '../../locales/vi/dashboard.json';
import { renderRoute, stubApi } from '../../test/fakeApi';
import { DashboardPage } from './DashboardPage';

const RUNS_PATH = 'GET /api/v1/admin/runs';

beforeEach(() => setToken('admin-token-dashboard', true));
afterEach(() => {
  setToken(null);
  vi.unstubAllGlobals();
});

describe('DashboardPage', () => {
  it('shows totals from server pages and account dates from the user list', async () => {
    const calls = stubApi({
      'GET /api/v1/users': {
        status: 200,
        body: [
          {
            id: 'u1',
            email: 'active@example.com',
            name: 'Active',
            role: 'MEMBER',
            status: 'ACTIVE',
            createdAt: new Date().toISOString(),
          },
          {
            id: 'u2',
            email: 'locked@example.com',
            name: 'Locked',
            role: 'MEMBER',
            status: 'SUSPENDED',
            createdAt: '2020-01-01T00:00:00Z',
          },
        ],
      },
      [RUNS_PATH]: () => {
        const status = calls.at(-1)?.query.get('status') ?? null;
        const totals: Record<string, number> = { running: 3, completed: 8, error: 2 };
        return {
          status: 200,
          body: {
            items:
              status === 'error'
                ? [
                    {
                      id: 'run-error',
                      workflowName: 'Product Video',
                      ownerEmail: 'active@example.com',
                      status: 'error',
                      startedAt: '2026-10-01T10:00:00Z',
                      errorMessage: 'private provider message',
                    },
                  ]
                : [],
            page: 0,
            size: 20,
            total: status ? totals[status] : 15,
          },
        };
      },
    });
    renderRoute('/dashboard', '/dashboard', <DashboardPage />);

    expect(await screen.findByText('15')).toBeInTheDocument();
    expect(screen.getByText('Total workflow runs')).toBeInTheDocument();
    expect(screen.getByText('New in 7 days')).toBeInTheDocument();
    expect(screen.getByText('Completed')).toBeInTheDocument();
    expect(screen.getByText('Recent failures')).toBeInTheDocument();
    expect(screen.queryByText('private provider message')).not.toBeInTheDocument();
  });

  describe('in Vietnamese', () => {
    beforeEach(async () => {
      await i18n.changeLanguage('vi');
    });
    afterEach(async () => {
      await i18n.changeLanguage('en');
    });

    it('renders the heading, metric cards, status pills and empty state in Vietnamese', async () => {
      stubApi({
        'GET /api/v1/users': { status: 200, body: [] },
        [RUNS_PATH]: { status: 200, body: { items: [], page: 0, size: 20, total: 1200 } },
      });
      renderRoute('/dashboard', '/dashboard', <DashboardPage />);

      expect(await screen.findByText(viDashboard.failures.empty)).toBeInTheDocument();
      expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(viDashboard.title);
      expect(screen.getByText(viDashboard.eyebrow)).toBeInTheDocument();
      expect(screen.getByText(viDashboard.failures.emptyHint)).toBeInTheDocument();
      expect(screen.getByText(viDashboard.metrics.totalRuns)).toBeInTheDocument();
      expect(screen.getByText(viDashboard.metrics.newUsersHint)).toBeInTheDocument();
      expect(screen.getByText(viDashboard.runStatus.failed)).toBeInTheDocument();
      expect(screen.getAllByText('1.200').length).toBeGreaterThan(0);
      expect(screen.getByRole('link', { name: viDashboard.runStatus.viewRuns })).toHaveAttribute(
        'href',
        '/runs',
      );
      expect(screen.getByRole('button', { name: viDashboard.refresh })).toBeInTheDocument();
    });
  });
});
