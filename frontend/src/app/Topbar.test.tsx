import { setToken } from '@flow-cortex/api-client';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import i18n from '../lib/i18n';
import { renderRoute, settled, stubApi, type FakeReply } from '../test/fakeApi';
import { Topbar } from './Topbar';

const FEATURES = {
  emailConfirmation: false,
  phoneVerification: false,
  uploadContentCheck: false,
  runCancellation: false,
  credits: true,
};

const ME = {
  status: 200,
  body: { id: 'u1', email: 'member1@flowcortex.dev', name: 'Member One', role: 'MEMBER' },
};

function stubWithMe(routes: Parameters<typeof stubApi>[0]) {
  return stubApi({ 'GET /api/v1/auth/me': ME, ...routes });
}

interface RenderOptions {
  showMenuButton?: boolean;
  drawerOpen?: boolean;
  onOpenMenu?: () => void;
}

function renderTopbar({
  showMenuButton = false,
  drawerOpen = false,
  onOpenMenu = () => {},
}: RenderOptions = {}) {
  return renderRoute(
    '/',
    '/',
    <Topbar showMenuButton={showMenuButton} drawerOpen={drawerOpen} onOpenMenu={onOpenMenu} />,
  );
}

function balanceCalls(calls: { path: string }[]) {
  return calls.filter((call) => call.path === '/api/v1/credits/balance');
}

describe('Topbar', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('offers the language toggle to a signed-in member', () => {
    stubApi({});

    renderTopbar();

    expect(screen.getByRole('group', { name: 'Language' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'VI Tiếng Việt' })).toBeInTheDocument();
  });

  it('holds the language toggle beside the theme toggle in the banner', () => {
    stubApi({});

    renderTopbar();

    const banner = screen.getByRole('banner');
    expect(within(banner).getByRole('group', { name: 'Language' })).toBeInTheDocument();
    expect(
      within(banner).getByRole('button', { name: /^Switch to (dark|light) theme$/ }),
    ).toBeInTheDocument();
    expect(within(banner).getByRole('button', { name: /open account menu/i })).toBeInTheDocument();
  });

  describe('in Vietnamese', () => {
    beforeEach(async () => {
      await i18n.changeLanguage('vi');
    });
    afterEach(async () => {
      await i18n.changeLanguage('en');
    });

    it('labels the theme switch and the language group next to it', () => {
      stubApi({});

      renderTopbar();

      expect(screen.getByRole('group', { name: 'Ngôn ngữ' })).toBeInTheDocument();
      expect(
        screen.getByRole('button', { name: /^Chuyển sang giao diện (tối|sáng)$/ }),
      ).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Mở menu tài khoản' })).toBeInTheDocument();
    });

    it('names the menu button in Vietnamese', () => {
      stubApi({});

      renderTopbar({ showMenuButton: true });

      expect(screen.getByRole('button', { name: 'Mở menu' })).toBeInTheDocument();
    });
  });
});

describe('Topbar menu button', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('is absent while the sidebar is pinned or a rail', () => {
    stubApi({});

    renderTopbar({ showMenuButton: false });

    expect(screen.queryByRole('button', { name: 'Open menu' })).not.toBeInTheDocument();
  });

  it('is named, hides its icon from assistive technology and opens the drawer', async () => {
    stubApi({});
    const onOpenMenu = vi.fn();
    const user = userEvent.setup();

    renderTopbar({ showMenuButton: true, onOpenMenu });

    const button = screen.getByRole('button', { name: 'Open menu' });
    expect(button.querySelector('svg')).toHaveAttribute('aria-hidden', 'true');
    expect(button).toHaveAttribute('aria-expanded', 'false');
    await user.click(button);
    expect(onOpenMenu).toHaveBeenCalledTimes(1);
  });

  it('reports the open drawer through aria-expanded', () => {
    stubApi({});

    renderTopbar({ showMenuButton: true, drawerOpen: true });

    expect(screen.getByRole('button', { name: 'Open menu' })).toHaveAttribute(
      'aria-expanded',
      'true',
    );
  });
});

describe('Topbar credits', () => {
  beforeEach(() => setToken('t', true));
  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
  });

  it('shows the balance from the credits endpoint, grouped the en-US way', async () => {
    stubWithMe({
      'GET /api/v1/features': { status: 200, body: FEATURES },
      'GET /api/v1/credits/balance': { status: 200, body: { balance: 1000 } },
    });

    renderTopbar();

    expect(await screen.findByText('1,000 credits')).toBeInTheDocument();
    expect(screen.queryByText(/GPU Credits/)).not.toBeInTheDocument();
  });

  it('shows no credits and never asks for the balance when credits are off', async () => {
    vi.stubEnv('VITE_ACCOUNT_STATS_ENABLED', 'true');
    const calls = stubWithMe({
      'GET /api/v1/features': { status: 200, body: { ...FEATURES, credits: false } },
      'GET /api/v1/credits/balance': { status: 200, body: { balance: 1000 } },
    });

    const { queryClient } = renderTopbar();
    await waitFor(() => expect(calls.some((call) => call.path === '/api/v1/features')).toBe(true));
    await settled(queryClient);

    expect(screen.queryByText(/credit/i)).not.toBeInTheDocument();
    expect(balanceCalls(calls)).toHaveLength(0);
  });

  it('shows no credits when the balance request fails', async () => {
    const calls = stubWithMe({
      'GET /api/v1/features': { status: 200, body: FEATURES },
      'GET /api/v1/credits/balance': {
        status: 503,
        body: { status: 503, detail: 'Credits are switched off' },
      },
    });

    const { queryClient } = renderTopbar();
    await waitFor(() => expect(balanceCalls(calls)).toHaveLength(1));
    await settled(queryClient);

    expect(screen.queryByText(/credit/i)).not.toBeInTheDocument();
  });

  it('reloads the balance once a run on screen reaches a terminal status', async () => {
    let reply: FakeReply = { status: 200, body: { balance: 1000 } };
    const calls = stubWithMe({
      'GET /api/v1/features': { status: 200, body: FEATURES },
      'GET /api/v1/credits/balance': () => reply,
    });

    const { queryClient } = renderTopbar();
    expect(await screen.findByText('1,000 credits')).toBeInTheDocument();

    act(() => queryClient.setQueryData(['runs', 'r1'], { id: 'r1', status: 'running' }));
    await settled(queryClient);
    expect(balanceCalls(calls)).toHaveLength(1);

    reply = { status: 200, body: { balance: 940 } };
    act(() => queryClient.setQueryData(['runs', 'r1'], { id: 'r1', status: 'completed' }));

    expect(await screen.findByText('940 credits')).toBeInTheDocument();
    expect(balanceCalls(calls)).toHaveLength(2);
  });

  describe('in Vietnamese', () => {
    beforeEach(async () => {
      await i18n.changeLanguage('vi');
    });
    afterEach(async () => {
      await i18n.changeLanguage('en');
    });

    it('groups the balance the vi-VN way', async () => {
      stubApi({
        'GET /api/v1/features': { status: 200, body: FEATURES },
        'GET /api/v1/credits/balance': { status: 200, body: { balance: 1000 } },
      });

      renderTopbar();

      expect(await screen.findByText('1.000 Credit')).toBeInTheDocument();
    });
  });
});
