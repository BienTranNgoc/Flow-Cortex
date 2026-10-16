import { setToken } from '@flow-cortex/api-client';
import { render, screen } from '@testing-library/react';
import { App } from './App';
import { stubApi } from './test/fakeApi';

vi.mock('./app/HomePage', () => ({
  HomePage: () => {
    throw new TypeError('Home exploded');
  },
}));

// An unauthenticated visit to any route redirects to /login rather than
// rendering a static "Flow-Cortex" heading.
describe('App', () => {
  it('redirects an unauthenticated visitor to the sign-in page', async () => {
    stubApi({ 'POST /api/v1/auth/refresh': { status: 401, body: { status: 401 } } });
    render(<App />);
    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
  });

  it('contains a page that throws inside the layout, with Reload and one structured event', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    stubApi({
      'GET /api/v1/auth/me': {
        status: 200,
        body: { id: 'u1', email: 'member1@flowcortex.dev', name: 'Member One', role: 'MEMBER' },
      },
      'GET /api/v1/features': {
        status: 200,
        body: {
          emailConfirmation: false,
          phoneVerification: false,
          uploadContentCheck: false,
          runCancellation: false,
          credits: false,
        },
      },
    });
    setToken('t', true);
    window.history.pushState({}, '', '/');

    render(<App />);

    expect(await screen.findByRole('button', { name: 'Reload' })).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: 'Main navigation' })).toBeInTheDocument();
    expect(consoleError.mock.calls.filter(([first]) => first === 'client_error')).toEqual([
      ['client_error', { route: '/', name: 'TypeError' }],
    ]);

    setToken(null);
    vi.unstubAllGlobals();
    consoleError.mockRestore();
  });

  it('sends an ADMIN who opens /users to / and shows no Users link', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    stubApi({
      'GET /api/v1/auth/me': {
        status: 200,
        body: { id: 'a1', email: 'admin@flowcortex.dev', name: 'Admin', role: 'ADMIN' },
      },
    });
    setToken('admin-token', true);
    window.history.pushState({}, '', '/users');

    render(<App />);

    expect(await screen.findByText('admin@flowcortex.dev')).toBeInTheDocument();
    expect(window.location.pathname).toBe('/');
    const nav = screen.getByRole('navigation', { name: 'Main navigation' });
    expect(nav).toHaveTextContent('Home');
    expect(nav).toHaveTextContent('Workflows');
    expect(nav).toHaveTextContent('Profile');
    expect(screen.queryByRole('link', { name: 'Users' })).not.toBeInTheDocument();

    setToken(null);
    vi.unstubAllGlobals();
    consoleError.mockRestore();
  });

  it('keeps Sign out in the header when /me fails', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    stubApi({ 'GET /api/v1/auth/me': { status: 500, body: { status: 500, detail: 'boom' } } });
    setToken('t', true);
    window.history.pushState({}, '', '/profile');

    render(<App />);

    expect(await screen.findByRole('button', { name: 'Retry' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Sign in' })).not.toBeInTheDocument();

    setToken(null);
    vi.unstubAllGlobals();
    consoleError.mockRestore();
  });
});
