import { setToken } from '@flow-cortex/api-client';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { App } from './App';
import { stubApi } from './test/fakeApi';

vi.mock('./app/HomePage', () => ({
  HomePage: () => {
    throw new TypeError('Home exploded');
  },
}));

const MEMBER_ME = {
  'GET /api/v1/auth/me': {
    status: 200,
    body: { id: 'u1', email: 'member1@flowcortex.dev', name: 'Member One', role: 'MEMBER' },
  },
};

describe('App', () => {
  it('redirects an unauthenticated visitor to the sign-in page', async () => {
    stubApi({ 'POST /api/v1/auth/refresh': { status: 401, body: { status: 401 } } });
    render(<App />);
    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
  });

  it('contains a page that throws inside the layout, with Reload and one structured event', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    stubApi(MEMBER_ME);
    setToken('t', true);
    window.history.pushState({}, '', '/');

    render(<App />);

    expect(await screen.findByRole('button', { name: 'Reload' })).toBeInTheDocument();
    const nav = screen.getByRole('navigation', { name: 'Main navigation' });
    expect(nav).toHaveTextContent('Home');
    expect(nav).toHaveTextContent('Workflows');
    expect(nav).toHaveTextContent('Docs');
    expect(nav).not.toHaveTextContent('Runs');
    expect(nav).not.toHaveTextContent('Integrations');
    expect(consoleError.mock.calls.filter(([first]) => first === 'client_error')).toEqual([
      ['client_error', { route: '/', name: 'TypeError' }],
    ]);

    setToken(null);
    vi.unstubAllGlobals();
    consoleError.mockRestore();
  });

  it('sends an ADMIN who opens /users to / and shows no Users link', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    const user = userEvent.setup();
    stubApi({
      'GET /api/v1/auth/me': {
        status: 200,
        body: { id: 'a1', email: 'admin@flowcortex.dev', name: 'Admin', role: 'ADMIN' },
      },
    });
    setToken('admin-token', true);
    window.history.pushState({}, '', '/users');

    render(<App />);

    expect(await screen.findByRole('button', { name: 'Reload' })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/');
    const nav = screen.getByRole('navigation', { name: 'Main navigation' });
    expect(nav).toHaveTextContent('Home');
    expect(nav).toHaveTextContent('Workflows');
    await user.click(screen.getByRole('button', { name: /open account menu/i }));
    expect(await screen.findByText('Admin', { selector: 'strong' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Account settings' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Users' })).not.toBeInTheDocument();

    setToken(null);
    vi.unstubAllGlobals();
    consoleError.mockRestore();
  });

  it.each(['/runs', '/integrations'])(
    'sends %s to / while the demo pages are off',
    async (path) => {
      const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
      stubApi(MEMBER_ME);
      setToken('t', true);
      window.history.pushState({}, '', path);

      render(<App />);

      expect(await screen.findByRole('button', { name: 'Reload' })).toBeInTheDocument();
      expect(window.location.pathname).toBe('/');

      setToken(null);
      vi.unstubAllGlobals();
      consoleError.mockRestore();
    },
  );

  it('lists Runs and Integrations in the nav when the demo pages flag is on', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    vi.stubEnv('VITE_DEMO_PAGES_ENABLED', 'true');
    stubApi(MEMBER_ME);
    setToken('t', true);
    window.history.pushState({}, '', '/runs');

    try {
      render(<App />);

      expect(await screen.findByRole('heading', { name: 'Recent runs' })).toBeInTheDocument();
      const nav = screen.getByRole('navigation', { name: 'Main navigation' });
      expect(within(nav).getByRole('link', { name: 'Runs' })).toBeInTheDocument();
      expect(within(nav).getByRole('link', { name: 'Integrations' })).toBeInTheDocument();
    } finally {
      setToken(null);
      vi.unstubAllEnvs();
      vi.unstubAllGlobals();
      consoleError.mockRestore();
    }
  });

  it('keeps Sign out in the header when /me fails', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    const user = userEvent.setup();
    stubApi({ 'GET /api/v1/auth/me': { status: 500, body: { status: 500, detail: 'boom' } } });
    setToken('t', true);
    window.history.pushState({}, '', '/profile');

    render(<App />);

    expect(await screen.findByRole('button', { name: 'Retry' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /open account menu/i }));
    expect(await screen.findByRole('button', { name: 'Sign out' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Sign in' })).not.toBeInTheDocument();

    setToken(null);
    vi.unstubAllGlobals();
    consoleError.mockRestore();
  });
});
