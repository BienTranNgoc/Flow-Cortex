import { setToken } from '@flow-cortex/api-client';
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ADMIN_ME, stubApi, testQueryClient } from '../test/fakeApi';
import { AdminLayout } from './AdminLayout';

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
const closeDialogDescriptor = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'close');

beforeEach(() => {
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
    configurable: true,
    value(this: HTMLDialogElement) {
      this.setAttribute('open', '');
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
    stubApi({ 'GET /api/v1/auth/me': { status: 200, body: ADMIN_ME } });
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
    expect(screen.getByRole('link', { name: /Media jobs/ })).toBeInTheDocument();
    expect(consoleError.mock.calls.filter(([first]) => first === 'client_error')).toEqual([
      ['client_error', { route: '/runs', name: 'TypeError' }],
    ]);
  });

  it('opens the account menu, shows identity, and closes after clicking outside', async () => {
    stubApi({ 'GET /api/v1/auth/me': { status: 200, body: ADMIN_ME } });
    setToken('admin-token-menu', true);
    renderAdminPage();

    const trigger = await screen.findByRole('button', { name: /account menu/i });
    expect(trigger).toHaveAttribute('aria-haspopup', 'menu');
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
    await userEvent.click(trigger);

    const menu = screen.getByRole('menu', { name: 'Account menu' });
    expect(trigger).toHaveAttribute('aria-expanded', 'true');
    expect(menu).toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'Signed-in account' })).toHaveTextContent(
      ADMIN_ME.email,
    );
    expect(screen.getByRole('menuitem', { name: 'Account' })).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: /Settings.*Coming soon/ })).toBeDisabled();
    expect(screen.queryByRole('menuitem', { name: /Language/ })).not.toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'Language' })).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Page action' }));
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
  });

  it('supports menu arrow navigation and Escape returns focus to the trigger', async () => {
    stubApi({ 'GET /api/v1/auth/me': { status: 200, body: ADMIN_ME } });
    setToken('admin-token-menu-keys', true);
    renderAdminPage();

    const trigger = await screen.findByRole('button', { name: /account menu/i });
    await userEvent.click(trigger);
    const account = screen.getByRole('menuitem', { name: 'Account' });
    const signOut = screen.getByRole('menuitem', { name: 'Sign out' });
    expect(account).toHaveFocus();

    await userEvent.keyboard('{ArrowDown}');
    expect(signOut).toHaveFocus();
    await userEvent.keyboard('{Escape}');

    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
    expect(trigger).toHaveFocus();
  });

  it('opens account details from the menu and restores focus after closing', async () => {
    stubApi({ 'GET /api/v1/auth/me': { status: 200, body: ADMIN_ME } });
    setToken('admin-token-account-details', true);
    renderAdminPage();

    const trigger = await screen.findByRole('button', { name: /account menu/i });
    await userEvent.click(trigger);
    await userEvent.click(screen.getByRole('menuitem', { name: 'Account' }));

    const dialog = await screen.findByRole('dialog', { name: 'Account' });
    expect(dialog).toHaveTextContent(ADMIN_ME.email);
    expect(dialog).toHaveTextContent(ADMIN_ME.role);
    await userEvent.click(screen.getByRole('button', { name: 'Close account details' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });
});
