import { getToken, setToken } from '@flow-cortex/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { stubApi } from '../test/fakeApi';
import { UserMenu } from './UserMenu';

function renderMenu() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <UserMenu name="Member One" email="member1@flowcortex.dev" />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('UserMenu', () => {
  beforeEach(() => setToken('t', true));
  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
  });

  it('renders a compact avatar trigger (no name text) and stays closed by default', () => {
    renderMenu();
    const trigger = screen.getByRole('button', { name: /open account menu/i });
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
    expect(trigger).toHaveTextContent('MO');
    expect(trigger).not.toHaveTextContent('Member One');
  });

  it('opens the dropdown on click and closes on Escape', async () => {
    const user = userEvent.setup();
    renderMenu();
    const trigger = screen.getByRole('button', { name: /open account menu/i });
    await user.click(trigger);
    const menu = await screen.findByRole('menu');
    expect(trigger).toHaveAttribute('aria-expanded', 'true');
    expect(within(menu).getByText('member1@flowcortex.dev')).toBeInTheDocument();
    expect(within(menu).getByText('Member One')).toBeInTheDocument();
    expect(within(menu).getByRole('menuitem', { name: 'Account settings' })).toBeInTheDocument();
    expect(within(menu).getByRole('menuitem', { name: 'Sign out' })).toBeInTheDocument();

    await user.keyboard('{Escape}');
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });

  it('shows the GPU and token quota when the account-stats flag is on', async () => {
    const user = userEvent.setup();
    vi.stubEnv('VITE_ACCOUNT_STATS_ENABLED', 'true');
    try {
      renderMenu();
      await user.click(screen.getByRole('button', { name: /open account menu/i }));
      const menu = await screen.findByRole('menu');
      expect(within(menu).getByText('GPU minutes')).toBeInTheDocument();
      expect(within(menu).getByText('Token balance')).toBeInTheDocument();
    } finally {
      vi.unstubAllEnvs();
    }
  });

  it('logs the user out when the Sign out item is clicked', async () => {
    const user = userEvent.setup();
    stubApi({ 'POST /api/v1/auth/logout': { status: 204, body: null } });
    renderMenu();
    await user.click(screen.getByRole('button', { name: /open account menu/i }));
    await user.click(await screen.findByRole('menuitem', { name: 'Sign out' }));
    expect(getToken()).toBeNull();
  });
});
