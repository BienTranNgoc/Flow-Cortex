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
    });
    setToken('t', true);
    window.history.pushState({}, '', '/');

    render(<App />);

    expect(await screen.findByRole('button', { name: 'Reload' })).toBeInTheDocument();
    const nav = screen.getByRole('navigation', { name: 'Main navigation' });
    expect(nav).toHaveTextContent('Home');
    expect(nav).toHaveTextContent('Workflows');
    expect(nav).toHaveTextContent('Runs');
    expect(nav).toHaveTextContent('Integrations');
    expect(nav).toHaveTextContent('Docs');
    expect(consoleError.mock.calls.filter(([first]) => first === 'client_error')).toEqual([
      ['client_error', { route: '/', name: 'TypeError' }],
    ]);

    setToken(null);
    vi.unstubAllGlobals();
    consoleError.mockRestore();
  });

  it('keeps Sign out in the header when /me fails', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    const user = userEvent.setup();
    stubApi({ 'GET /api/v1/auth/me': { status: 500, body: { status: 500, detail: 'boom' } } });
    setToken('t', true);
    window.history.pushState({}, '', '/profile');

    render(<App />);

    expect(await screen.findByRole('button', { name: 'Retry' })).toBeInTheDocument();
    const trigger = screen.getByRole('button', { name: /open account menu/i });
    await user.click(trigger);
    const menu = await screen.findByRole('menu');
    expect(within(menu).getByRole('menuitem', { name: 'Sign out' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Sign in' })).not.toBeInTheDocument();

    setToken(null);
    vi.unstubAllGlobals();
    consoleError.mockRestore();
  });
});
