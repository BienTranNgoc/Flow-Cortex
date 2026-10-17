import { setToken } from '@flow-cortex/api-client';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { App } from '../App';
import { ADMIN_ME, MEMBER_ME, settled, stubApi, testQueryClient } from '../test/fakeApi';
import { queryClient } from './queryClient';
import { RequireAdminApp } from './RequireAdminApp';

const EMPTY_PAGE = { items: [], page: 0, size: 20, total: 0 };

afterEach(() => {
  setToken(null);
  queryClient.clear();
  vi.unstubAllGlobals();
});

describe('RequireAdminApp', () => {
  it('sends a signed-out visitor to /login and back to /runs after an ADMIN login', async () => {
    const calls = stubApi({
      'POST /api/v1/auth/refresh': { status: 401, body: { status: 401 } },
      'POST /api/v1/auth/login': {
        status: 200,
        body: { accessToken: 'admin-token', expiresIn: 900 },
      },
      'GET /api/v1/auth/me': { status: 200, body: ADMIN_ME },
      'GET /api/v1/admin/runs': { status: 200, body: EMPTY_PAGE },
    });
    window.history.pushState({}, '', '/runs');
    render(<App />);

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/login');

    await userEvent.type(screen.getByLabelText('Email'), ADMIN_ME.email);
    await userEvent.type(screen.getByLabelText('Password'), 'secret');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('heading', { name: 'Runs' })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/runs');
    expect(calls.find((call) => call.path === '/api/v1/auth/login')?.body).toEqual({
      email: ADMIN_ME.email,
      password: 'secret',
      rememberMe: true,
    });
  });

  it('gets a token from the refresh cookie before sending a signed-out visitor to /login', async () => {
    const calls = stubApi({
      'POST /api/v1/auth/refresh': {
        status: 200,
        body: { accessToken: 'boot-token', expiresIn: 900 },
      },
      'GET /api/v1/auth/me': { status: 200, body: ADMIN_ME },
    });
    render(
      <QueryClientProvider client={testQueryClient()}>
        <MemoryRouter initialEntries={['/runs']}>
          <Routes>
            <Route
              path="/runs"
              element={
                <RequireAdminApp>
                  <p>admin content</p>
                </RequireAdminApp>
              }
            />
            <Route path="/login" element={<p>login page</p>} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(await screen.findByText('admin content')).toBeInTheDocument();
    expect(calls.filter((call) => call.path === '/api/v1/auth/refresh')).toHaveLength(1);
  });

  it('calls the logout endpoint on sign-out', async () => {
    const calls = stubApi({
      'GET /api/v1/auth/me': { status: 200, body: MEMBER_ME },
      'POST /api/v1/auth/logout': { status: 204 },
    });
    setToken('member-token-logout', true);
    window.history.pushState({}, '', '/runs');
    render(<App />);

    await userEvent.click(await screen.findByRole('button', { name: 'Sign out' }));

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    expect(calls.map((call) => `${call.method} ${call.path}`)).toContain(
      'POST /api/v1/auth/logout',
    );
  });

  it('shows a MEMBER "No access" with sign-out and makes no admin API call', async () => {
    const calls = stubApi({ 'GET /api/v1/auth/me': { status: 200, body: MEMBER_ME } });
    setToken('member-token', true);
    window.history.pushState({}, '', '/runs');
    render(<App />);

    expect(await screen.findByRole('heading', { name: 'No access' })).toBeInTheDocument();
    expect(screen.getByText(/does not have the ADMIN role/)).toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Admin navigation' })).not.toBeInTheDocument();
    await settled(queryClient);
    expect(calls.map((call) => call.path)).toEqual(['/api/v1/auth/me']);

    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
  });

  it('drops every cached admin result on sign-out', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ADMIN_ME },
      'GET /api/v1/admin/runs': { status: 200, body: EMPTY_PAGE },
    });
    setToken('admin-token-signout', true);
    window.history.pushState({}, '', '/runs');
    render(<App />);
    expect(await screen.findByText('No runs yet.')).toBeInTheDocument();
    expect(queryClient.getQueryCache().findAll({ queryKey: ['admin'] })).not.toHaveLength(0);

    await userEvent.click(screen.getByRole('button', { name: /account menu/i }));
    await userEvent.click(screen.getByRole('menuitem', { name: 'Sign out' }));

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    expect(queryClient.getQueryCache().findAll({ queryKey: ['admin'] })).toHaveLength(0);
    expect(
      queryClient.getQueryCache().findAll({ queryKey: ['me', 'admin-token-signout'] }),
    ).toHaveLength(0);
  });

  it('renders its children for an ADMIN', async () => {
    stubApi({ 'GET /api/v1/auth/me': { status: 200, body: ADMIN_ME } });
    setToken('admin-token-children', true);
    render(
      <QueryClientProvider client={testQueryClient()}>
        <MemoryRouter initialEntries={['/runs']}>
          <RequireAdminApp>
            <p>admin content</p>
          </RequireAdminApp>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(await screen.findByText('admin content')).toBeInTheDocument();
  });

  it('shows a retryable error when the signed-in user cannot be loaded', async () => {
    stubApi({ 'GET /api/v1/auth/me': { status: 500, body: { status: 500, detail: 'boom' } } });
    setToken('admin-token-error', true);
    render(
      <QueryClientProvider client={testQueryClient()}>
        <MemoryRouter initialEntries={['/runs']}>
          <RequireAdminApp>
            <p>admin content</p>
          </RequireAdminApp>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not check your account.');
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
    expect(screen.queryByText('admin content')).not.toBeInTheDocument();
  });
});
