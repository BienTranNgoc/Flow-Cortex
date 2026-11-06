import { setToken } from '@flow-cortex/api-client';
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { App } from '../App';
import i18n from '../lib/i18n';
import viCommon from '../locales/vi/common.json';
import { ADMIN_ME, stubApi, testQueryClient } from '../test/fakeApi';
import { queryClient } from './queryClient';
import { Sidebar } from './Sidebar';

const OPEN_COUNT_PATH = '/api/v1/admin/support/requests/open-count';

function renderSidebar(role: string, collapsed = false) {
  return render(
    <QueryClientProvider client={testQueryClient()}>
      <MemoryRouter>
        <Sidebar role={role} collapsed={collapsed} onToggle={() => {}} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

afterEach(() => {
  setToken(null);
  queryClient.clear();
  vi.unstubAllGlobals();
});

describe('Sidebar', () => {
  it('links dashboard and permitted entries, and shows disabled items as inert text', () => {
    stubApi({ [`GET ${OPEN_COUNT_PATH}`]: { status: 200, body: { count: 0 } } });
    renderSidebar('ADMIN');

    expect(screen.getByRole('link', { name: /Dashboard/ })).toHaveAttribute('href', '/dashboard');
    expect(screen.getByRole('link', { name: /Runs/ })).toHaveAttribute('href', '/runs');
    expect(screen.getByRole('link', { name: /Media Jobs/i })).toHaveAttribute('href', '/media-jobs');
    expect(screen.getByRole('link', { name: /Users/ })).toHaveAttribute('href', '/users');
    expect(screen.getByRole('link', { name: /SMS Outbox/ })).toHaveAttribute('href', '/sms-outbox');

    const voice = screen.getByText('Voice Quota').closest('[aria-disabled]');
    expect(voice).toHaveAttribute('aria-disabled', 'true');
    expect(voice).toHaveTextContent('Coming soon');
    expect(voice?.querySelector('.cortex-admin-nav__visually-hidden')).toHaveTextContent(
      'unavailable',
    );
    expect(voice?.closest('a')).toBeNull();
    expect(screen.queryByRole('link', { name: /Voice Quota/ })).not.toBeInTheDocument();
  });

  it.each([
    'Workflows & Templates',
    'Approval Queue',
    'Credits & Wallets',
    'Model Providers',
    'Feature Switches',
    'Audit Log',
    'Voice Quota',
    'Health',
  ])(
    'shows %s as a disabled "Coming soon" entry, never a link',
    (label) => {
      stubApi({ [`GET ${OPEN_COUNT_PATH}`]: { status: 200, body: { count: 0 } } });
      renderSidebar('ADMIN');

      const entry = screen.getByText(label).closest('[aria-disabled]');
      expect(entry).toHaveAttribute('aria-disabled', 'true');
      expect(entry).toHaveTextContent('Coming soon');
      expect(screen.queryByRole('link', { name: new RegExp(label, 'i') })).not.toBeInTheDocument();
    },
  );

  it('shows no entry a role may not use', () => {
    const calls = stubApi({});
    renderSidebar('MEMBER');

    expect(screen.queryAllByRole('link')).toHaveLength(0);
    expect(calls).toHaveLength(0);
  });

  it('groups the Help Center pages under "Support" with the open request count on Requests', async () => {
    stubApi({ [`GET ${OPEN_COUNT_PATH}`]: { status: 200, body: { count: 4 } } });
    renderSidebar('ADMIN');

    const group = screen.getByRole('list', { name: 'Support' });
    expect(group).toContainElement(screen.getByRole('link', { name: 'Guide articles' }));
    expect(screen.getByRole('link', { name: 'Topics' })).toHaveAttribute('href', '/support/groups');
    expect(screen.getByRole('link', { name: 'Assistant settings' })).toHaveAttribute(
      'href',
      '/support/settings',
    );
    const requests = await screen.findByRole('link', { name: 'Requests, 4 open requests' });
    expect(requests).toHaveAttribute('href', '/support/requests');
    expect(requests).toHaveTextContent('4');
  });

  it('names a single open request in the singular', async () => {
    stubApi({ [`GET ${OPEN_COUNT_PATH}`]: { status: 200, body: { count: 1 } } });
    renderSidebar('ADMIN');

    expect(await screen.findByRole('link', { name: 'Requests, 1 open request' })).toBeInTheDocument();
  });

  it('shows no count on Requests while nothing is open', async () => {
    const calls = stubApi({ [`GET ${OPEN_COUNT_PATH}`]: { status: 200, body: { count: 0 } } });
    renderSidebar('ADMIN');

    await vi.waitFor(() => expect(calls.some((call) => call.path === OPEN_COUNT_PATH)).toBe(true));
    expect(screen.getByRole('link', { name: 'Requests' })).not.toHaveTextContent(/\d/);
  });

  it('falls back to Dashboard when the address bar names a disabled entry', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ADMIN_ME },
      'GET /api/v1/admin/runs': { status: 200, body: { items: [], page: 0, size: 20, total: 0 } },
      'GET /api/v1/users': { status: 200, body: [] },
      [`GET ${OPEN_COUNT_PATH}`]: { status: 200, body: { count: 0 } },
    });
    setToken('admin-token-voice', true);
    window.history.pushState({}, '', '/voice-quota');
    render(<App />);

    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/dashboard');
  });

  it('switches between expanded and icon-only mode', async () => {
    stubApi({ [`GET ${OPEN_COUNT_PATH}`]: { status: 200, body: { count: 0 } } });
    const { container } = renderSidebar('ADMIN');
    expect(container.querySelector('.cortex-admin-nav')).not.toHaveClass('cortex-admin-nav--collapsed');
    expect(screen.getByText('Dashboard')).toBeVisible();

    renderSidebar('ADMIN', true);
    expect(screen.getAllByRole('link').length).toBeGreaterThan(0);
  });

  it('does not render the collapse toggle inside the sidebar (header hosts it instead)', () => {
    stubApi({ [`GET ${OPEN_COUNT_PATH}`]: { status: 200, body: { count: 0 } } });
    renderSidebar('ADMIN');
    expect(screen.queryByRole('button', { name: 'Collapse sidebar' })).not.toBeInTheDocument();
  });

  describe('in Vietnamese', () => {
    beforeEach(async () => {
      await i18n.changeLanguage('vi');
    });
    afterEach(async () => {
      await i18n.changeLanguage('en');
    });

    it('labels the groups, links, disabled entries and system status in Vietnamese', () => {
      stubApi({ [`GET ${OPEN_COUNT_PATH}`]: { status: 200, body: { count: 0 } } });
      renderSidebar('ADMIN');
      const { nav } = viCommon.shell;

      expect(screen.getByRole('navigation', { name: nav.label })).toBeInTheDocument();
      expect(screen.getByText(nav.groups.workflowOps)).toBeInTheDocument();
      expect(screen.getByRole('link', { name: nav.items.runs })).toHaveAttribute('href', '/runs');
      expect(screen.getByRole('link', { name: nav.items.mediaJobs })).toHaveAttribute(
        'href',
        '/media-jobs',
      );

      const voice = screen.getByText(nav.items.voiceQuota).closest('[aria-disabled]');
      expect(voice).toHaveTextContent(nav.comingSoon);
      expect(voice).toHaveAttribute('title', `${nav.items.voiceQuota} — ${nav.comingSoon}`);
      expect(voice?.querySelector('.cortex-admin-nav__visually-hidden')).toHaveTextContent(
        nav.unavailable,
      );
      expect(screen.getByText(nav.systemStatus)).toBeInTheDocument();
    });
  });
});
