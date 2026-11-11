import { setToken } from '@flow-cortex/api-client';
import { render, screen, within } from '@testing-library/react';
import { App } from '../App';
import { staffMe, stubApi } from '../test/fakeApi';
import { queryClient } from './queryClient';

const EMPTY_PAGE = { items: [], page: 0, size: 20, total: 0 };

// A caller who reads runs but not the SMS outbox, narrowed through `/api/v1/auth/me`.
const RUNS_ONLY = staffMe('OPERATION_MANAGER', ['workflow.run.view']);

afterEach(() => {
  setToken(null);
  queryClient.clear();
  vi.unstubAllGlobals();
});

describe('a partial grant', () => {
  it('hides the entry and refuses its route with no second edit', async () => {
    const calls = stubApi({
      'GET /api/v1/auth/me': { status: 200, body: RUNS_ONLY },
      'GET /api/v1/admin/runs': { status: 200, body: EMPTY_PAGE },
    });
    setToken('admin-token-partial', true);
    window.history.pushState({}, '', '/sms-outbox');
    render(<App />);

    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/dashboard');
    expect(screen.queryByRole('link', { name: /SMS Outbox/ })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Runs/ })).toBeInTheDocument();
    expect(calls.some((call) => call.path === '/api/v1/admin/sms-outbox')).toBe(false);
  });

  it('shows only the entries whose key the caller holds, and the inert ones', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: RUNS_ONLY },
      'GET /api/v1/admin/runs': { status: 200, body: EMPTY_PAGE },
    });
    setToken('admin-token-partial-keys', true);
    window.history.pushState({}, '', '/dashboard');
    render(<App />);

    const nav = await screen.findByRole('navigation', { name: 'Admin navigation' });
    expect(within(nav).getByRole('link', { name: /Media Jobs/ })).toBeInTheDocument();
    for (const hidden of [/Users/, /Admin accounts/, /Requests/, /Guide articles/, /Topics/]) {
      expect(within(nav).queryByRole('link', { name: hidden })).not.toBeInTheDocument();
    }
    expect(within(nav).getByText('Workflows & Templates').closest('[aria-disabled="true"]')).not.toBeNull();
  });

  it('keeps the brand with its Admin label and marks the Dashboard entry as current', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: RUNS_ONLY },
      'GET /api/v1/admin/runs': { status: 200, body: EMPTY_PAGE },
    });
    setToken('admin-token-partial-brand', true);
    window.history.pushState({}, '', '/dashboard');
    render(<App />);

    const nav = await screen.findByRole('navigation', { name: 'Admin navigation' });
    const sidebar = nav.closest('aside') as HTMLElement;
    expect(within(sidebar).getByText('nodi')).toBeInTheDocument();
    expect(within(sidebar).getByText('Admin')).toBeInTheDocument();
    expect(within(nav).getByRole('link', { name: /Dashboard/ })).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(within(nav).queryByRole('link', { name: /SMS Outbox/i })).not.toBeInTheDocument();
  });
});
