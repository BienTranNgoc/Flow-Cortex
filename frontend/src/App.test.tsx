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
  it('redirects an unauthenticated visitor to the sign-in page', () => {
    render(<App />);
    expect(screen.getByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
  });

  it('contains a page that throws inside the layout, with Reload and one structured event', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    stubApi({
      'GET /api/v1/auth/me': {
        status: 200,
        body: { id: 'u1', email: 'member1@flowcortex.dev', name: 'Member One', role: 'MEMBER' },
      },
    });
    setToken('t');
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
    setToken('admin-token');
    window.history.pushState({}, '', '/users');

    render(<App />);

    expect(await screen.findByText('admin@flowcortex.dev')).toBeInTheDocument();
    expect(window.location.pathname).toBe('/');
    const nav = screen.getByRole('navigation', { name: 'Main navigation' });
    expect(nav).toHaveTextContent('Workflows');
    expect(screen.queryByRole('link', { name: 'Users' })).not.toBeInTheDocument();

    setToken(null);
    vi.unstubAllGlobals();
    consoleError.mockRestore();
  });
});
