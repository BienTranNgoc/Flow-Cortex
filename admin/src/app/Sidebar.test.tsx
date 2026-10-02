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
  it('links every permitted entry and shows a disabled one as inert "Coming soon" text', () => {
    render(
      <MemoryRouter>
        <Sidebar role="ADMIN" />
      </MemoryRouter>,
    );

    expect(screen.getByRole('link', { name: /Runs/ })).toHaveAttribute('href', '/runs');
    expect(screen.getByRole('link', { name: /Media jobs/ })).toHaveAttribute('href', '/media-jobs');
    expect(screen.getByRole('link', { name: /Users/ })).toHaveAttribute('href', '/users');

    const providers = screen.getByText('Providers').closest('[aria-disabled]');
    expect(providers).toHaveAttribute('aria-disabled', 'true');
    expect(providers).toHaveTextContent('Coming soon');
    expect(providers?.querySelector('.visually-hidden')).toHaveTextContent('unavailable');
    expect(providers?.closest('a')).toBeNull();
    expect(screen.queryByRole('link', { name: /Providers/ })).not.toBeInTheDocument();
  });

  it.each(['Providers', 'Usage', 'Settings', 'Audit log'])(
    'shows %s as a disabled "Coming soon" entry, never a link',
    (label) => {
      render(
        <MemoryRouter>
          <Sidebar role="ADMIN" />
        </MemoryRouter>,
      );

      const entry = screen.getByText(label).closest('[aria-disabled]');
      expect(entry).toHaveAttribute('aria-disabled', 'true');
      expect(entry).toHaveTextContent('Coming soon');
      expect(screen.queryByRole('link', { name: new RegExp(label) })).not.toBeInTheDocument();
    },
  );

  it('shows no entry a role may not use', () => {
    render(
      <MemoryRouter>
        <Sidebar role="MEMBER" />
      </MemoryRouter>,
    );

    expect(screen.queryAllByRole('link')).toHaveLength(0);
  });

  it('falls back to Runs when the address bar names a disabled entry', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ADMIN_ME },
      'GET /api/v1/admin/runs': { status: 200, body: { items: [], page: 0, size: 20, total: 0 } },
    });
    setToken('admin-token-providers');
    window.history.pushState({}, '', '/providers');
    render(<App />);

    expect(await screen.findByRole('heading', { name: 'Runs' })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/runs');
  });
});
