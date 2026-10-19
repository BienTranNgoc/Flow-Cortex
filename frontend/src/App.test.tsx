import { setToken } from '@flow-cortex/api-client';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { App } from './App';
import type * as LoginPageModule from './features/auth/LoginPage';
import { stubApi } from './test/fakeApi';

const loginPage = vi.hoisted(() => ({ throws: false }));

vi.mock('./app/HomePage', () => ({
  HomePage: () => {
    throw new TypeError('Home exploded');
  },
}));

vi.mock('./features/auth/LoginPage', async (importOriginal) => {
  const actual = await importOriginal<typeof LoginPageModule>();
  return {
    LoginPage: () => {
      if (loginPage.throws) {
        throw new TypeError('Login exploded');
      }
      return <actual.LoginPage />;
    },
  };
});

// An unauthenticated visit to any route redirects to /login rather than
// rendering a static "Flow-Cortex" heading.
describe('App', () => {
  it('redirects an unauthenticated visitor to the sign-in page', async () => {
    stubApi({ 'POST /api/v1/auth/refresh': { status: 401, body: { status: 401 } } });
    window.history.pushState({}, '', '/');
    render(<App />);
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Chào mừng đến với Nodi Studio' }),
    ).toBeInTheDocument();
    expect(window.location.pathname).toBe('/login');
    vi.unstubAllGlobals();
  });

  it('shows the fallback with its Reload button when the sign-in page throws', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    loginPage.throws = true;
    stubApi({});
    window.history.pushState({}, '', '/login');

    render(<App />);

    expect(await screen.findByRole('button', { name: 'Reload' })).toBeInTheDocument();
    expect(consoleError.mock.calls.filter(([first]) => first === 'client_error')).toEqual([
      ['client_error', { route: '/login', name: 'TypeError' }],
    ]);

    loginPage.throws = false;
    vi.unstubAllGlobals();
    consoleError.mockRestore();
  });

  it('frames /login with one main landmark, no aurora background and no footer', async () => {
    stubApi({});
    window.history.pushState({}, '', '/login');

    render(<App />);

    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument();
    expect(screen.getAllByRole('main')).toHaveLength(1);
    expect(document.querySelector('.aurora-bg')).toBeNull();
    expect(document.querySelector('.cortex-footer')).toBeNull();
    vi.unstubAllGlobals();
  });

  it('moves focus to the register heading after the create-account link, not on first load', async () => {
    stubApi({});
    window.history.pushState({}, '', '/login');
    const user = userEvent.setup();

    render(<App />);

    const loginHeading = await screen.findByRole('heading', { level: 1 });
    expect(loginHeading).not.toHaveFocus();
    await user.click(screen.getByRole('link', { name: 'Tạo tài khoản' }));

    const registerHeading = await screen.findByRole('heading', {
      level: 1,
      name: 'Bắt đầu cùng Nodi Studio',
    });
    expect(window.location.pathname).toBe('/register');
    await waitFor(() => expect(registerHeading).toHaveFocus());
    vi.unstubAllGlobals();
  });

  it('keeps the same banner image and card nodes from /login to /register', async () => {
    stubApi({});
    window.history.pushState({}, '', '/login');
    const user = userEvent.setup();

    render(<App />);

    await screen.findByRole('heading', { level: 1, name: 'Chào mừng đến với Nodi Studio' });
    const banner = document.querySelector('.auth-stage__art');
    const card = document.querySelector('.auth-card');
    expect(banner).not.toBeNull();
    expect(card).not.toBeNull();

    await user.click(screen.getByRole('link', { name: 'Tạo tài khoản' }));
    await screen.findByRole('heading', { level: 1, name: 'Bắt đầu cùng Nodi Studio' });

    expect(document.querySelector('.auth-stage__art')).toBe(banner);
    expect(document.querySelector('.auth-card')).toBe(card);
    vi.unstubAllGlobals();
  });

  it('drops the focus flag after focusing so a reload stays put, keeping the return path', async () => {
    stubApi({});
    window.history.pushState({ usr: { from: '/profile' }, key: 'start', idx: 0 }, '', '/login');
    const user = userEvent.setup();

    render(<App />);

    await user.click(await screen.findByRole('link', { name: 'Tạo tài khoản' }));
    const registerHeading = await screen.findByRole('heading', {
      level: 1,
      name: 'Bắt đầu cùng Nodi Studio',
    });
    await waitFor(() => expect(registerHeading).toHaveFocus());
    await waitFor(() =>
      expect(window.history.state.usr).toEqual({ from: '/profile', focusHeading: false }),
    );
    vi.unstubAllGlobals();
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
