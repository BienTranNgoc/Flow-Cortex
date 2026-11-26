import { setToken } from '@flow-cortex/api-client';
import { QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import i18n from '../lib/i18n';
import viCommon from '../locales/vi/common.json';
import viUsers from '../locales/vi/users.json';
import { ADMIN_ME, stubApi, testQueryClient } from '../test/fakeApi';
import { stubMatchMedia } from '../test/matchMedia';
import { AdminLayout } from './AdminLayout';

const BELL_ROUTES = {
  'GET /api/v1/notifications/unread-count': { status: 200, body: { unreadCount: 3 } },
};

function Exploding(): never {
  throw new TypeError('Runs exploded');
}

function renderAdminPage() {
  return render(
    <QueryClientProvider client={testQueryClient()}>
      <MemoryRouter initialEntries={['/runs']}>
        <Routes>
          <Route element={<AdminLayout />}>
            <Route path="runs" element={<button type="button">Page action</button>} />
            <Route path="users" element={<p>Users page</p>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
const showModalDescriptor = Object.getOwnPropertyDescriptor(
  HTMLDialogElement.prototype,
  'showModal',
);
const closeDialogDescriptor = Object.getOwnPropertyDescriptor(
  HTMLDialogElement.prototype,
  'close',
);

beforeEach(() => {
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
    configurable: true,
    value(this: HTMLDialogElement) {
      this.setAttribute('open', '');
      this.querySelector<HTMLElement>('a[href], button:not(:disabled)')?.focus();
    },
  });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', {
    configurable: true,
    value(this: HTMLDialogElement) {
      this.removeAttribute('open');
      this.dispatchEvent(new Event('close'));
    },
  });
});

afterEach(() => {
  setToken(null);
  vi.unstubAllGlobals();
  if (showModalDescriptor) {
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', showModalDescriptor);
  } else {
    Reflect.deleteProperty(HTMLDialogElement.prototype, 'showModal');
  }
  if (closeDialogDescriptor) {
    Object.defineProperty(HTMLDialogElement.prototype, 'close', closeDialogDescriptor);
  } else {
    Reflect.deleteProperty(HTMLDialogElement.prototype, 'close');
  }
  consoleError.mockClear();
});
afterAll(() => consoleError.mockRestore());

describe('AdminLayout', () => {
  it('contains a page that throws inside the shell, with Reload and one structured event', async () => {
    stubApi({ ...BELL_ROUTES, 'GET /api/v1/auth/me': { status: 200, body: ADMIN_ME } });
    setToken('admin-token-layout', true);
    window.history.pushState({}, '', '/runs');

    render(
      <QueryClientProvider client={testQueryClient()}>
        <MemoryRouter initialEntries={['/runs']}>
          <Routes>
            <Route element={<AdminLayout />}>
              <Route path="runs" element={<Exploding />} />
            </Route>
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(await screen.findByRole('button', { name: 'Reload' })).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: /account menu/i })).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: 'Admin navigation' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Media Jobs/i })).toBeInTheDocument();
    expect(consoleError.mock.calls.filter(([first]) => first === 'client_error')).toEqual([
      ['client_error', { route: '/runs', name: 'TypeError' }],
    ]);
  });

  it('opens the account menu, shows identity, and closes after clicking outside', async () => {
    stubApi({ ...BELL_ROUTES, 'GET /api/v1/auth/me': { status: 200, body: ADMIN_ME } });
    setToken('admin-token-menu', true);
    renderAdminPage();

    const trigger = await screen.findByRole('button', { name: /account menu/i });
    expect(trigger).toHaveAttribute('aria-haspopup', 'menu');
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
    await userEvent.click(trigger);

    const menu = screen.getByRole('menu', { name: 'Account menu' });
    expect(trigger).toHaveAttribute('aria-expanded', 'true');
    expect(menu).toBeInTheDocument();
    expect(menu).toHaveTextContent(ADMIN_ME.email);
    expect(menu).toHaveTextContent('Admin');
    expect(menu).not.toHaveTextContent(ADMIN_ME.role);
    expect(screen.getByRole('menuitem', { name: 'Account details' })).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: 'Sign out' })).toBeInTheDocument();
    expect(screen.queryByRole('menuitem', { name: /Language/ })).not.toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'Language' })).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Page action' }));
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
  });

  it('shows the notification bell with its unread count before the language toggle', async () => {
    stubApi({ ...BELL_ROUTES, 'GET /api/v1/auth/me': { status: 200, body: ADMIN_ME } });
    setToken('admin-token-bell', true);
    renderAdminPage();

    const bell = await screen.findByRole('button', { name: 'Notifications, 3 unread' });
    const language = screen.getByRole('group', { name: 'Language' });
    expect(bell.compareDocumentPosition(language) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('shows a custom role by its name in the account menu and the account dialog', async () => {
    const custom = { ...ADMIN_ME, role: 'HO_TRO_NANG_CAO', roleName: 'Advanced support' };
    stubApi({ ...BELL_ROUTES, 'GET /api/v1/auth/me': { status: 200, body: custom } });
    setToken('admin-token-custom-role', true);
    renderAdminPage();

    await userEvent.click(await screen.findByRole('button', { name: /account menu/i }));
    const menu = screen.getByRole('menu', { name: 'Account menu' });
    expect(menu).toHaveTextContent('Advanced support');
    expect(menu).not.toHaveTextContent(custom.role);

    await userEvent.click(screen.getByRole('menuitem', { name: 'Account details' }));
    const dialog = await screen.findByRole('dialog', { name: 'Account' });
    expect(dialog).toHaveTextContent('Advanced support');
    expect(dialog).not.toHaveTextContent(custom.role);
  });

  it('supports menu arrow navigation and Escape returns focus to the trigger', async () => {
    stubApi({ ...BELL_ROUTES, 'GET /api/v1/auth/me': { status: 200, body: ADMIN_ME } });
    setToken('admin-token-menu-keys', true);
    renderAdminPage();

    const trigger = await screen.findByRole('button', { name: /account menu/i });
    await userEvent.click(trigger);
    const account = screen.getByRole('menuitem', { name: 'Account details' });
    const signOut = screen.getByRole('menuitem', { name: 'Sign out' });
    expect(screen.queryByRole('menuitem', { name: 'Help & docs' })).not.toBeInTheDocument();
    expect(account).toHaveFocus();

    await userEvent.keyboard('{ArrowDown}');
    expect(signOut).toHaveFocus();
    await userEvent.keyboard('{Escape}');

    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
    expect(trigger).toHaveFocus();
  });

  it('opens account details from the menu and restores focus after closing', async () => {
    stubApi({ ...BELL_ROUTES, 'GET /api/v1/auth/me': { status: 200, body: ADMIN_ME } });
    setToken('admin-token-account-details', true);
    renderAdminPage();

    const trigger = await screen.findByRole('button', { name: /account menu/i });
    await userEvent.click(trigger);
    await userEvent.click(screen.getByRole('menuitem', { name: 'Account details' }));

    const dialog = await screen.findByRole('dialog', { name: 'Account' });
    expect(dialog).toHaveTextContent(ADMIN_ME.email);
    expect(dialog).toHaveTextContent('Admin');
    expect(dialog).not.toHaveTextContent(ADMIN_ME.role);
    expect(dialog).not.toHaveTextContent('Two-factor authentication');
    expect(dialog).not.toHaveTextContent('Session timeout');
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Close account dialog' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  describe('sidebar modes', () => {
    afterEach(() => localStorage.clear());

    it('honours a collapsed choice stored before the redesign and renders the rail', async () => {
      localStorage.setItem('flow-cortex:admin:sidebar:collapsed', '1');
      stubApi({ ...BELL_ROUTES, 'GET /api/v1/auth/me': { status: 200, body: ADMIN_ME } });
      setToken('admin-token-rail', true);
      renderAdminPage();

      const nav = await screen.findByRole('navigation', { name: 'Admin navigation' });
      expect(nav.closest('aside')).toHaveClass('cortex-admin-nav--collapsed');
      expect(screen.getByRole('button', { name: 'Expand sidebar' })).toHaveAttribute(
        'aria-pressed',
        'true',
      );
    });

    it('collapses from the control inside the sidebar and keeps the rail after a reload', async () => {
      stubApi({ ...BELL_ROUTES, 'GET /api/v1/auth/me': { status: 200, body: ADMIN_ME } });
      setToken('admin-token-toggle', true);
      const first = renderAdminPage();

      const nav = await screen.findByRole('navigation', { name: 'Admin navigation' });
      await userEvent.click(
        within(nav.closest('aside') as HTMLElement).getByRole('button', { name: 'Collapse sidebar' }),
      );
      expect(localStorage.getItem('flow-cortex:admin:sidebar:collapsed')).toBe('1');
      first.unmount();

      renderAdminPage();
      const reloaded = await screen.findByRole('navigation', { name: 'Admin navigation' });
      expect(reloaded.closest('aside')).toHaveClass('cortex-admin-nav--collapsed');
    });
  });

  describe('drawer below 1024px', () => {
    it('opens from the topbar menu button and returns focus to it after a choice', async () => {
      stubMatchMedia(false);
      stubApi({ ...BELL_ROUTES, 'GET /api/v1/auth/me': { status: 200, body: ADMIN_ME } });
      setToken('admin-token-drawer', true);
      renderAdminPage();

      const menu = await screen.findByRole('button', { name: 'Open menu' });
      expect(menu.querySelector('svg')).toHaveAttribute('aria-hidden', 'true');
      expect(screen.queryByRole('dialog', { name: 'Admin navigation' })).not.toBeInTheDocument();

      await userEvent.click(menu);
      const dialog = screen.getByRole('dialog', { name: 'Admin navigation' });
      expect(within(dialog).getByRole('button', { name: 'Close menu' })).toHaveFocus();

      await userEvent.click(within(dialog).getByRole('link', { name: /Users/ }));
      expect(screen.queryByRole('dialog', { name: 'Admin navigation' })).not.toBeInTheDocument();
      expect(await screen.findByText('Users page')).toBeInTheDocument();
      expect(menu).toHaveFocus();
    });

    it('closes when the viewport reaches 1024px and puts focus on the main region', async () => {
      const media = stubMatchMedia(false);
      stubApi({ ...BELL_ROUTES, 'GET /api/v1/auth/me': { status: 200, body: ADMIN_ME } });
      setToken('admin-token-grow', true);
      const { container } = renderAdminPage();

      await userEvent.click(await screen.findByRole('button', { name: 'Open menu' }));
      expect(screen.getByRole('dialog', { name: 'Admin navigation' })).toBeInTheDocument();

      act(() => media.setMatches(true));

      expect(container.querySelector('dialog.cortex-admin-nav')).toBeNull();
      expect(container.querySelector('aside.cortex-admin-nav')).not.toBeNull();
      expect(screen.getByRole('main')).toHaveFocus();
    });
  });

  describe('in Vietnamese', () => {
    beforeEach(async () => {
      await i18n.changeLanguage('vi');
    });
    afterEach(async () => {
      await i18n.changeLanguage('en');
    });

    it('labels the header, its toggles and the account menu in Vietnamese', async () => {
      stubApi({ ...BELL_ROUTES, 'GET /api/v1/auth/me': { status: 200, body: ADMIN_ME } });
      setToken('admin-token-layout-vi', true);
      renderAdminPage();

      const trigger = await screen.findByRole('button', {
        name: `${ADMIN_ME.name}, menu tài khoản`,
      });
      expect(screen.getByRole('group', { name: viCommon.language.groupLabel })).toBeInTheDocument();
      expect(
        await screen.findByRole('button', { name: viCommon.bell.label.replace('{{unread}}', '3') }),
      ).toBeInTheDocument();
      expect(
        screen.getByRole('button', {
          name: new RegExp(`${viCommon.theme.switchToLight}|${viCommon.theme.switchToDark}`),
        }),
      ).toBeInTheDocument();
      expect(document.querySelector('.cortex-admin-nav__brand-label')).toHaveTextContent(
        viCommon.shell.brandAdmin,
      );
      expect(screen.getByRole('button', { name: viCommon.shell.nav.openMenu })).toBeInTheDocument();
      expect(
        screen.getAllByRole('button', { name: viCommon.shell.nav.collapse }).length,
      ).toBeGreaterThan(0);
      expect(screen.getByRole('navigation', { name: viCommon.shell.nav.label })).toBeInTheDocument();

      await userEvent.click(trigger);
      const menu = screen.getByRole('menu', { name: viCommon.account.menu });
      expect(menu).toHaveTextContent(viUsers.role.ADMIN);
      expect(screen.getByRole('menuitem', { name: viCommon.account.details })).toBeInTheDocument();
      expect(screen.getByRole('menuitem', { name: viCommon.account.signOut })).toBeInTheDocument();

      await userEvent.click(screen.getByRole('menuitem', { name: viCommon.account.details }));
      const dialog = await screen.findByRole('dialog', { name: viCommon.account.dialogTitle });
      expect(dialog).toHaveTextContent(viCommon.account.dangerZone);
      expect(screen.getByRole('button', { name: viCommon.account.copyEmail })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: viCommon.account.close })).toBeInTheDocument();
    });
  });
});
