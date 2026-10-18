import { setToken } from '@flow-cortex/api-client';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { App } from '../App';
import { ADMIN_ME, stubApi } from '../test/fakeApi';
import { queryClient } from './queryClient';
import { Sidebar } from './Sidebar';

afterEach(() => {
  setToken(null);
  queryClient.clear();
  vi.unstubAllGlobals();
});

describe('Sidebar', () => {
  it('links dashboard and permitted entries, and shows disabled items as inert text', () => {
    render(
      <MemoryRouter>
        <Sidebar role="ADMIN" collapsed={false} onToggle={() => {}} />
      </MemoryRouter>,
    );

    expect(screen.getByRole('link', { name: /Dashboard/ })).toHaveAttribute('href', '/dashboard');
    expect(screen.getByRole('link', { name: /Runs/ })).toHaveAttribute('href', '/runs');
    expect(screen.getByRole('link', { name: /Media Jobs/i })).toHaveAttribute('href', '/media-jobs');
    expect(screen.getByRole('link', { name: /Users/ })).toHaveAttribute('href', '/users');
    expect(screen.getByRole('link', { name: /Workflows/ })).toHaveAttribute('href', '/workflows');
    expect(screen.getByRole('link', { name: /Approval Queue/ })).toHaveAttribute('href', '/approvals');
    expect(screen.getByRole('link', { name: /Credits & Wallets/ })).toHaveAttribute('href', '/credits');
    expect(screen.getByRole('link', { name: /Model Providers/ })).toHaveAttribute('href', '/providers');
    expect(screen.getByRole('link', { name: /SMS Outbox/ })).toHaveAttribute('href', '/sms-outbox');
    expect(screen.getByRole('link', { name: /Feature Switches/ })).toHaveAttribute('href', '/features');
    expect(screen.getByRole('link', { name: /Audit Log/ })).toHaveAttribute('href', '/audit');

    const voice = screen.getByText('Voice Quota').closest('[aria-disabled]');
    expect(voice).toHaveAttribute('aria-disabled', 'true');
    expect(voice).toHaveTextContent('Coming soon');
    expect(voice?.querySelector('.cortex-admin-nav__visually-hidden')).toHaveTextContent(
      'unavailable',
    );
    expect(voice?.closest('a')).toBeNull();
    expect(screen.queryByRole('link', { name: /Voice Quota/ })).not.toBeInTheDocument();
  });

  it.each(['Voice Quota', 'Health'])(
    'shows %s as a disabled "Coming soon" entry, never a link',
    (label) => {
      render(
        <MemoryRouter>
          <Sidebar role="ADMIN" collapsed={false} onToggle={() => {}} />
        </MemoryRouter>,
      );

      const entry = screen.getByText(label).closest('[aria-disabled]');
      expect(entry).toHaveAttribute('aria-disabled', 'true');
      expect(entry).toHaveTextContent('Coming soon');
      expect(screen.queryByRole('link', { name: new RegExp(label, 'i') })).not.toBeInTheDocument();
    },
  );

  it('shows no entry a role may not use', () => {
    render(
      <MemoryRouter>
        <Sidebar role="MEMBER" collapsed={false} onToggle={() => {}} />
      </MemoryRouter>,
    );

    expect(screen.queryAllByRole('link')).toHaveLength(0);
  });

  it('falls back to Dashboard when the address bar names a disabled entry', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ADMIN_ME },
      'GET /api/v1/admin/runs': { status: 200, body: { items: [], page: 0, size: 20, total: 0 } },
      'GET /api/v1/users': { status: 200, body: [] },
    });
    setToken('admin-token-voice', true);
    window.history.pushState({}, '', '/voice-quota');
    render(<App />);

    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/dashboard');
  });

  it('switches between expanded and icon-only mode', async () => {
    const { container } = render(
      <MemoryRouter>
        <Sidebar role="ADMIN" collapsed={false} onToggle={() => {}} />
      </MemoryRouter>,
    );
    expect(container.querySelector('.cortex-admin-nav')).not.toHaveClass('cortex-admin-nav--collapsed');
    expect(screen.getByText('Dashboard')).toBeVisible();

    render(
      <MemoryRouter>
        <Sidebar role="ADMIN" collapsed onToggle={() => {}} />
      </MemoryRouter>,
    );
    expect(screen.getAllByRole('link').length).toBeGreaterThan(0);
  });

  it('does not render the collapse toggle inside the sidebar (header hosts it instead)', () => {
    render(
      <MemoryRouter>
        <Sidebar role="ADMIN" collapsed={false} onToggle={() => {}} />
      </MemoryRouter>,
    );
    expect(screen.queryByRole('button', { name: 'Collapse sidebar' })).not.toBeInTheDocument();
  });
});
