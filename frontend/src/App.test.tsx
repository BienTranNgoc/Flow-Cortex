import { setToken } from '@flow-cortex/api-client';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { App } from './App';
import type * as LoginPageModule from './features/auth/LoginPage';
import i18n from './lib/i18n';
import { stubApi } from './test/fakeApi';

const loginPage = vi.hoisted(() => ({ throws: false }));
const pages = vi.hoisted(() => ({ homeThrows: true }));

vi.mock('./app/HomePage', () => ({
  HomePage: () => {
    if (pages.homeThrows) throw new TypeError('Home exploded');
    return (
      <>
        <h1>Home page</h1>
        <a href="/workflows/1">First workflow</a>
      </>
    );
  },
}));

vi.mock('./features/workflow/WorkflowDetailPage', () => ({
  WorkflowDetailPage: () => {
    throw new TypeError('Detail exploded');
  },
}));

vi.mock('./features/workflow/WorkflowsListPage', async () => {
  const { PageHeading } = await import('./app/PageHeading');
  return { WorkflowsListPage: () => <PageHeading title="Workflows" /> };
});

afterEach(() => {
  pages.homeThrows = true;
});

const MEMBER_ME = {
  'GET /api/v1/auth/me': {
    status: 200,
    body: { id: 'u1', email: 'member1@flowcortex.dev', name: 'Member One', role: 'MEMBER' },
  },
};

const FEATURES_OFF = {
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
};

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

describe('App', () => {
  it('redirects an unauthenticated visitor to the sign-in page', async () => {
    stubApi({ 'POST /api/v1/auth/refresh': { status: 401, body: { status: 401 } } });
    render(<App />);
    expect(
      await screen.findByRole('heading', { name: 'Welcome to Nodi Studio' }),
    ).toBeInTheDocument();
  });

  it('shows the fallback with its Reload button when the sign-in page throws', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    loginPage.throws = true;
    stubApi(FEATURES_OFF);
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

  describe('in Vietnamese', () => {
    beforeEach(async () => {
      await i18n.changeLanguage('vi');
    });
    afterEach(async () => {
      await i18n.changeLanguage('en');
    });

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
  });

  it('contains a page that throws inside the layout, with Try again and one structured event', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    stubApi({ ...MEMBER_ME, ...FEATURES_OFF });
    setToken('t', true);
    window.history.pushState({}, '', '/');

    render(<App />);

    expect(await screen.findByRole('button', { name: 'Try again' })).toBeInTheDocument();
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

  it('keeps the sidebar usable after a page throws, and Home opens without the error view', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    const user = userEvent.setup();
    stubApi({ ...MEMBER_ME, ...FEATURES_OFF });
    setToken('t', true);
    window.history.pushState({}, '', '/workflows/42');

    render(<App />);

    expect(await screen.findByRole('button', { name: 'Try again' })).toBeInTheDocument();
    expect(screen.getByText('Something went wrong')).toBeInTheDocument();
    pages.homeThrows = false;
    const nav = screen.getByRole('navigation', { name: 'Main navigation' });
    await user.click(within(nav).getByRole('link', { name: 'Home' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'Home page' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();
    expect(screen.queryByText('Something went wrong')).not.toBeInTheDocument();

    setToken(null);
    vi.unstubAllGlobals();
    consoleError.mockRestore();
  });

  it('frames /workflows with one navigation, one banner and one main landmark', async () => {
    stubApi({ ...MEMBER_ME, ...FEATURES_OFF });
    setToken('t', true);
    window.history.pushState({}, '', '/workflows');

    render(<App />);

    expect(await screen.findByRole('heading', { level: 1, name: 'Workflows' })).toBeInTheDocument();
    expect(screen.getAllByRole('navigation')).toHaveLength(1);
    expect(screen.getAllByRole('main')).toHaveLength(1);
    expect(screen.getAllByRole('banner')).toHaveLength(1);
    expect(screen.getByRole('main')).toContainElement(
      screen.getByRole('heading', { level: 1, name: 'Workflows' }),
    );

    setToken(null);
    vi.unstubAllGlobals();
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

    expect(await screen.findByRole('button', { name: 'Try again' })).toBeInTheDocument();
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

      expect(await screen.findByRole('button', { name: 'Try again' })).toBeInTheDocument();
      expect(window.location.pathname).toBe('/');

      setToken(null);
      vi.unstubAllGlobals();
      consoleError.mockRestore();
    },
  );

  it('lists Runs and Integrations in the sidebar nav when the demo pages flag is on', async () => {
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
      expect(nav.closest('aside')).not.toBeNull();
    } finally {
      setToken(null);
      vi.unstubAllEnvs();
      vi.unstubAllGlobals();
      consoleError.mockRestore();
    }
  });

  it('keeps Sign out in the topbar when /me fails', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    const user = userEvent.setup();
    stubApi({ 'GET /api/v1/auth/me': { status: 500, body: { status: 500, detail: 'boom' } } });
    setToken('t', true);
    window.history.pushState({}, '', '/profile');

    render(<App />);

    expect(await screen.findByRole('button', { name: 'Retry' })).toBeInTheDocument();
    const topbar = screen.getByRole('banner');
    await user.click(within(topbar).getByRole('button', { name: /open account menu/i }));
    expect(await within(topbar).findByRole('button', { name: 'Sign out' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Sign in' })).not.toBeInTheDocument();

    setToken(null);
    vi.unstubAllGlobals();
    consoleError.mockRestore();
  });

  describe('skip link and page-change focus', () => {
    beforeEach(() => {
      pages.homeThrows = false;
      stubApi({ ...MEMBER_ME, ...FEATURES_OFF });
      setToken('t', true);
      window.history.pushState({}, '', '/');
    });
    afterEach(() => {
      setToken(null);
      vi.unstubAllGlobals();
    });

    it('moves no focus on a direct load, so the first Tab reaches the skip link', async () => {
      const user = userEvent.setup();
      render(<App />);

      await screen.findByRole('heading', { level: 1, name: 'Home page' });
      expect(document.body).toHaveFocus();

      await user.tab();
      expect(screen.getByRole('link', { name: 'Skip to content' })).toHaveFocus();
    });

    it('puts focus in main after one Tab and Enter, before the first page control', async () => {
      const user = userEvent.setup();
      render(<App />);
      await screen.findByRole('heading', { level: 1, name: 'Home page' });

      await user.tab();
      await user.keyboard('{Enter}');

      expect(screen.getByRole('main')).toHaveFocus();
      expect(screen.getByRole('link', { name: 'First workflow' })).not.toHaveFocus();
    });

    it('focuses the new page heading after choosing Workflows in the sidebar', async () => {
      const user = userEvent.setup();
      render(<App />);
      await screen.findByRole('heading', { level: 1, name: 'Home page' });

      const nav = screen.getByRole('navigation', { name: 'Main navigation' });
      await user.click(within(nav).getByRole('link', { name: 'Workflows' }));

      const heading = await screen.findByRole('heading', { level: 1, name: 'Workflows' });
      await waitFor(() => expect(heading).toHaveFocus());
    });
  });
});
