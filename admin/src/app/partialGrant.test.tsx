import { setToken } from '@flow-cortex/api-client';
import { render, screen } from '@testing-library/react';
import { App } from '../App';
import { ADMIN_ME, stubApi } from '../test/fakeApi';
import type * as Permissions from './permissions';
import { queryClient } from './queryClient';

// The ADMIN row without one action: the sidebar and the router must both drop it.
vi.mock('./permissions', async (importOriginal) => {
  const actual = await importOriginal<typeof Permissions>();
  return {
    ...actual,
    can: (role: string | undefined, action: Permissions.Action) =>
      action !== 'mediaJobs.view' && actual.can(role, action),
  };
});

afterEach(() => {
  setToken(null);
  queryClient.clear();
  vi.unstubAllGlobals();
});

describe('a partial grant', () => {
  it('hides the entry and refuses its route with no second edit', async () => {
    const calls = stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ADMIN_ME },
      'GET /api/v1/admin/runs': { status: 200, body: { items: [], page: 0, size: 20, total: 0 } },
      'GET /api/v1/users': { status: 200, body: [] },
    });
    setToken('admin-token-partial', true);
    window.history.pushState({}, '', '/media-jobs');
    render(<App />);

    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/dashboard');
    expect(screen.queryByRole('link', { name: /Media jobs/ })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Runs/ })).toBeInTheDocument();
    expect(calls.some((call) => call.path === '/api/v1/admin/media/jobs')).toBe(false);
  });
});
