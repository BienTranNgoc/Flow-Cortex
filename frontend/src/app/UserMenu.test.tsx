import { getToken, setToken } from '@flow-cortex/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, renderHook, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import {
  resetOnboardingSession,
  useOnboardingSession,
} from '../features/onboarding/onboardingSession';
import i18n from '../lib/i18n';
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

function panelOf(trigger: HTMLElement): HTMLElement {
  const panel = document.getElementById(trigger.getAttribute('aria-controls') ?? '');
  if (!panel) throw new Error('the trigger controls no rendered panel');
  return panel;
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

  it('opens a disclosure panel on click; Escape closes it and returns focus to the trigger', async () => {
    const user = userEvent.setup();
    renderMenu();
    const trigger = screen.getByRole('button', { name: /open account menu/i });
    await user.click(trigger);
    const panel = panelOf(trigger);
    expect(trigger).toHaveAttribute('aria-expanded', 'true');
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(within(panel).getByText('member1@flowcortex.dev')).toBeInTheDocument();
    expect(within(panel).getByText('Member One')).toBeInTheDocument();
    expect(within(panel).getByRole('link', { name: 'Account settings' })).toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: 'Sign out' })).toBeInTheDocument();

    await user.tab();
    expect(within(panel).getByRole('link', { name: 'Account settings' })).toHaveFocus();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('link', { name: 'Account settings' })).not.toBeInTheDocument();
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
    expect(trigger).toHaveFocus();
  });

  it('hides Connected services while the demo pages are off and shows it when on', async () => {
    const user = userEvent.setup();
    const { unmount } = renderMenu();
    await user.click(screen.getByRole('button', { name: /open account menu/i }));
    expect(screen.queryByRole('link', { name: 'Connected services' })).not.toBeInTheDocument();
    unmount();

    vi.stubEnv('VITE_DEMO_PAGES_ENABLED', 'true');
    try {
      renderMenu();
      await user.click(screen.getByRole('button', { name: /open account menu/i }));
      expect(screen.getByRole('link', { name: 'Connected services' })).toHaveAttribute(
        'href',
        '/integrations',
      );
    } finally {
      vi.unstubAllEnvs();
    }
  });

  it('shows the GPU and token quota when the account-stats flag is on', async () => {
    const user = userEvent.setup();
    vi.stubEnv('VITE_ACCOUNT_STATS_ENABLED', 'true');
    try {
      renderMenu();
      const trigger = screen.getByRole('button', { name: /open account menu/i });
      await user.click(trigger);
      const menu = panelOf(trigger);
      expect(within(menu).getByText('GPU minutes')).toBeInTheDocument();
      expect(within(menu).getByText('Token balance')).toBeInTheDocument();
    } finally {
      vi.unstubAllEnvs();
    }
  });

  describe('in Vietnamese', () => {
    beforeEach(async () => {
      await i18n.changeLanguage('vi');
    });
    afterEach(async () => {
      await i18n.changeLanguage('en');
    });

    it('labels the trigger and every item, and groups the quota the vi-VN way', async () => {
      const user = userEvent.setup();
      vi.stubEnv('VITE_ACCOUNT_STATS_ENABLED', 'true');
      vi.stubEnv('VITE_DEMO_PAGES_ENABLED', 'true');
      try {
        renderMenu();
        const trigger = screen.getByRole('button', {
          name: 'Mở menu tài khoản của member1@flowcortex.dev',
        });
        await user.click(trigger);
        const panel = panelOf(trigger);

        expect(within(panel).getByRole('link', { name: 'Cài đặt tài khoản' })).toBeInTheDocument();
        expect(within(panel).getByRole('link', { name: 'Dịch vụ đã kết nối' })).toBeInTheDocument();
        expect(
          within(panel).getByRole('link', { name: 'Trợ giúp và tài liệu' }),
        ).toBeInTheDocument();
        expect(within(panel).getByRole('button', { name: 'Đăng xuất' })).toBeInTheDocument();
        expect(within(panel).getByText('Phút GPU')).toBeInTheDocument();
        expect(within(panel).getByText('84.500')).toBeInTheDocument();
      } finally {
        vi.unstubAllEnvs();
      }
    });
  });

  it('logs the user out when the Sign out item is clicked', async () => {
    const user = userEvent.setup();
    stubApi({ 'POST /api/v1/auth/logout': { status: 204, body: null } });
    renderMenu();
    await user.click(screen.getByRole('button', { name: /open account menu/i }));
    await user.click(await screen.findByRole('button', { name: 'Sign out' }));
    expect(getToken()).toBeNull();
  });

  describe('Replay guide', () => {
    const FEATURES = {
      emailConfirmation: false,
      phoneVerification: false,
      uploadContentCheck: false,
      runCancellation: false,
      credits: false,
      passwordReset: false,
      supportWidget: false,
      supportAssistant: false,
      onboarding: true,
    };

    async function openMenu(entry: string | null) {
      const user = userEvent.setup();
      const calls = stubApi({
        'GET /api/v1/features': { status: 200, body: FEATURES },
        'GET /api/v1/onboarding/status': { status: 200, body: { entry, progress: {} } },
      });
      renderMenu();
      await waitFor(() =>
        expect(calls.some((call) => call.path === '/api/v1/onboarding/status')).toBe(true),
      );
      await new Promise((resolve) => setTimeout(resolve, 30));
      await user.click(screen.getByRole('button', { name: /open account menu/i }));
      return user;
    }

    it('is listed once the status has loaded with no entry, and starts a replay', async () => {
      const user = await openMenu(null);
      await user.click(await screen.findByRole('button', { name: 'Replay guide' }));
      expect(screen.queryByRole('button', { name: 'Replay guide' })).not.toBeInTheDocument();
      expect(renderHook(() => useOnboardingSession()).result.current.replayRequested).toBe(true);
      act(() => resetOnboardingSession());
    });

    it('is not listed while the first-time journey is due', async () => {
      await openMenu('first');
      expect(screen.queryByRole('button', { name: 'Replay guide' })).not.toBeInTheDocument();
    });
  });
});
