import { setToken } from '@flow-cortex/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { App } from '../../App';
import { queryClient as appQueryClient } from '../../app/queryClient';
import i18n from '../../lib/i18n';
import { LoginPage } from '../auth/LoginPage';
import { RequireAuth } from '../auth/RequireAuth';
import { stubApi, type Handler } from '../../test/fakeApi';
import { SupportWidget } from './SupportWidget';

const FEATURED = [
  { slug: 'khong-dang-nhap-duoc', title: 'Không đăng nhập được?', position: 3 },
  { slug: 'nodi-lam-duoc-gi', title: 'Nodi làm được gì?', position: 1 },
  { slug: 'bat-dau-tu-dau', title: 'Bắt đầu từ đâu?', position: 2 },
];

const ARTICLE = {
  slug: 'bat-dau-tu-dau',
  title: 'Bắt đầu từ đâu?',
  body: 'Mở **Workflows** và chọn một preset.',
  groupId: 'g1',
  groupName: 'Bắt đầu',
  publishedAt: '2026-10-19T00:00:00Z',
  updatedAt: '2026-10-19T00:00:00Z',
};

function homeApi(contactEmail?: string): Record<string, Handler> {
  return {
    'GET /api/v1/support/kb/featured': { status: 200, body: FEATURED },
    'GET /api/v1/support/settings': {
      status: 200,
      body:
        contactEmail === undefined
          ? { assistantAvailable: true }
          : { contactEmail, assistantAvailable: true },
    },
    'GET /api/v1/support/kb/articles/bat-dau-tu-dau': { status: 200, body: ARTICLE },
  };
}

function renderWidget(url = '/workflows', page: ReactElement = <p>Trang</p>) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[url]}>
        <Routes>
          <Route path="*" element={page} />
        </Routes>
        <SupportWidget />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { queryClient };
}

async function openPanel() {
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: 'Help' }));
  return { user, panel: screen.getByRole('dialog', { name: 'Help Center' }) };
}

function SearchProbe() {
  return <p data-testid="search">{useLocation().search}</p>;
}

function tabNames() {
  return screen.getAllByRole('tab').map((tab) => tab.textContent);
}

afterEach(() => {
  setToken(null);
  window.localStorage.clear();
  window.sessionStorage.clear();
  vi.unstubAllGlobals();
});

describe('SupportPanel', () => {
  it('opens from the help button with focus inside the panel', async () => {
    stubApi(homeApi());
    renderWidget('/login');

    const launcher = screen.getByRole('button', { name: 'Help' });
    expect(launcher).toHaveAttribute('aria-expanded', 'false');
    const { panel } = await openPanel();

    expect(launcher).toHaveAttribute('aria-expanded', 'true');
    expect(panel).toContainElement(document.activeElement as HTMLElement);
  });

  it('closes on Escape and gives focus back to the help button', async () => {
    setToken('member', true);
    stubApi(homeApi());
    renderWidget('/workflows');
    const { user } = await openPanel();

    await user.keyboard('{Escape}');

    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByRole('button', { name: 'Help' })).toHaveFocus();
  });

  it('closes from the close button and keeps the page underneath as it was', async () => {
    stubApi(homeApi());
    renderWidget('/login', <input aria-label="Email" defaultValue="" />);
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('Email'), 'an@example.com');
    await openPanel();

    await user.click(screen.getByRole('button', { name: 'Close' }));

    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByLabelText('Email')).toHaveValue('an@example.com');
  });

  it('offers a guest exactly Home and Assistant', async () => {
    stubApi(homeApi());
    renderWidget('/login');
    await openPanel();

    expect(tabNames()).toEqual(['Home', 'Assistant']);
  });

  it('offers a member four tabs and moves focus along them with the right arrow', async () => {
    setToken('member', true);
    stubApi(homeApi());
    renderWidget('/workflows');
    const { user } = await openPanel();

    expect(tabNames()).toEqual(['Home', 'Assistant', 'Guides', 'Requests']);
    const home = screen.getByRole('tab', { name: 'Home' });
    expect(home).toHaveAttribute('aria-selected', 'true');
    home.focus();

    const visited: string[] = [];
    for (let i = 0; i < 3; i += 1) {
      await user.keyboard('{ArrowRight}');
      visited.push(document.activeElement?.textContent ?? '');
    }

    expect(visited).toEqual(['Assistant', 'Guides', 'Requests']);
    expect(screen.getByRole('tab', { name: 'Requests' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tab', { name: 'Home' })).toHaveAttribute('tabindex', '-1');
  });

  it('leaves the panel open when Escape is pressed in a page field', async () => {
    stubApi(homeApi());
    renderWidget('/login', <input aria-label="Ghi chú" />);
    const { user } = await openPanel();

    await user.click(screen.getByLabelText('Ghi chú'));
    await user.keyboard('{Escape}');

    expect(screen.getByRole('dialog', { name: 'Help Center' })).toBeInTheDocument();
  });

  it('marks the Requests tab while a reply is unread', async () => {
    setToken('member', true);
    stubApi({
      ...homeApi(),
      'GET /api/v1/features': { status: 200, body: { supportWidget: true } },
      'GET /api/v1/support/requests/unread-count': { status: 200, body: { count: 1 } },
    });
    renderWidget('/workflows');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Help. New reply' }));
    const panel = screen.getByRole('dialog', { name: 'Help Center' });

    const tab = within(panel).getByRole('tab', { name: 'Requests. New reply' });
    expect(tab.querySelector('.support-unread-dot')).not.toBeNull();
  });

  it('never asks a guest for unread replies', async () => {
    const calls = stubApi({
      ...homeApi(),
      'GET /api/v1/features': { status: 200, body: { supportWidget: true } },
    });
    const { queryClient } = renderWidget('/login');
    await openPanel();

    await waitFor(() => expect(queryClient.isFetching()).toBe(0));
    expect(calls.filter((call) => call.path.includes('unread-count'))).toEqual([]);
  });
});

describe('small screens', () => {
  function screenIsSmall(small: boolean) {
    vi.stubGlobal(
      'matchMedia',
      vi.fn((media: string) => ({
        matches: small,
        media,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
      })),
    );
  }

  it('makes the full-screen panel modal and the page inert below 640px, until it closes', async () => {
    screenIsSmall(true);
    stubApi(homeApi());
    renderWidget('/login', <a href="/register">Tạo tài khoản</a>);
    const { user, panel } = await openPanel();

    expect(panel).toHaveAttribute('aria-modal', 'true');
    expect(screen.getByText('Tạo tài khoản').closest('[inert]')).not.toBeNull();

    await user.click(within(panel).getByRole('button', { name: 'Close' }));

    expect(screen.getByText('Tạo tài khoản').closest('[inert]')).toBeNull();
    expect(screen.getByRole('button', { name: 'Help' })).toHaveFocus();
  });

  it('keeps the desktop panel non-modal', async () => {
    screenIsSmall(false);
    stubApi(homeApi());
    renderWidget('/login', <a href="/register">Tạo tài khoản</a>);
    const { panel } = await openPanel();

    expect(panel).not.toHaveAttribute('aria-modal');
    expect(screen.getByText('Tạo tài khoản').closest('[inert]')).toBeNull();
  });
});

describe('article focus', () => {
  it('keeps focus in the panel while an article loads, then moves it to the title', async () => {
    let answer: (reply: { status: number; body: unknown }) => void = () => {};
    stubApi({
      ...homeApi(),
      'GET /api/v1/support/kb/articles/bat-dau-tu-dau': () =>
        new Promise((resolve) => {
          answer = resolve;
        }),
    });
    renderWidget('/login');
    const { user, panel } = await openPanel();

    await user.click(await within(panel).findByRole('button', { name: 'Bắt đầu từ đâu?' }));

    await waitFor(() => expect(panel).toContainElement(document.activeElement as HTMLElement));
    expect(document.activeElement).not.toBe(document.body);
    answer({ status: 200, body: ARTICLE });
    const title = await within(panel).findByRole('heading', { name: 'Bắt đầu từ đâu?' });
    await waitFor(() => expect(title).toHaveFocus());
  });

  it('announces an unpublished article and focuses its way back', async () => {
    stubApi({
      ...homeApi(),
      'GET /api/v1/support/kb/articles/bat-dau-tu-dau': {
        status: 404,
        body: { status: 404, code: 'article-not-found' },
      },
    });
    renderWidget('/login');
    const { user, panel } = await openPanel();

    await user.click(await within(panel).findByRole('button', { name: 'Bắt đầu từ đâu?' }));

    expect(await within(panel).findByRole('status')).toHaveTextContent(
      'This article is no longer published',
    );
    await waitFor(() =>
      expect(within(panel).getByRole('button', { name: 'Back to Home' })).toHaveFocus(),
    );
  });
});

describe('Help Center switch', () => {
  afterEach(() => {
    appQueryClient.clear();
    window.history.pushState({}, '', '/');
  });

  const features = (supportWidget: boolean) => ({
    status: 200,
    body: {
      emailConfirmation: false,
      phoneVerification: false,
      uploadContentCheck: false,
      runCancellation: false,
      credits: false,
      supportWidget,
      supportAssistant: false,
    },
  });

  it('shows no help button and calls no support endpoint while the switch is off', async () => {
    const calls = stubApi({ 'GET /api/v1/features': features(false) });
    window.history.pushState({}, '', '/login');

    render(<App />);

    await waitFor(() => expect(calls.some((call) => call.path === '/api/v1/features')).toBe(true));
    await waitFor(() => expect(appQueryClient.isFetching()).toBe(0));
    expect(screen.queryByRole('button', { name: 'Help' })).toBeNull();
    expect(calls.filter((call) => call.path.startsWith('/api/v1/support'))).toEqual([]);
  });

  it('shows the help button on the sign-in page while the switch is on', async () => {
    stubApi({ 'GET /api/v1/features': features(true), ...homeApi() });
    window.history.pushState({}, '', '/login');

    render(<App />);

    expect(await screen.findByRole('button', { name: 'Help' })).toBeInTheDocument();
  });
});

describe('help link', () => {
  it.each([
    ['a guarded page', '/?help=request-12'],
    ['the sign-in page', '/login?help=request-12'],
  ])(
    'opened signed out on %s survives sign-in and opens request #12 on Requests',
    async (_page, url) => {
      stubApi({
        'POST /api/v1/auth/refresh': { status: 401, body: { status: 401 } },
        'POST /api/v1/auth/login': { status: 200, body: { accessToken: 'jwt-1', expiresIn: 900 } },
        ...homeApi(),
      });
      const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
      render(
        <QueryClientProvider client={queryClient}>
          <MemoryRouter initialEntries={[url]}>
            <Routes>
              <Route
                path="/"
                element={
                  <RequireAuth>
                    <p>Bảng điều khiển</p>
                  </RequireAuth>
                }
              />
              <Route path="/login" element={<LoginPage />} />
            </Routes>
            <SupportWidget />
          </MemoryRouter>
        </QueryClientProvider>,
      );
      const user = userEvent.setup();

      await user.type(await screen.findByLabelText('Email'), 'member1@flowcortex.dev');
      await user.type(screen.getByLabelText('Password'), 'secret');
      expect(screen.queryByRole('dialog')).toBeNull();
      await user.click(screen.getByRole('button', { name: /Sign in to Studio/ }));

      expect(await screen.findByText('Bảng điều khiển')).toBeInTheDocument();
      const panel = await screen.findByRole('dialog', { name: 'Help Center' });
      expect(within(panel).getByRole('tab', { name: 'Requests' })).toHaveAttribute(
        'aria-selected',
        'true',
      );
      expect(within(panel).getByText('Request #12')).toBeInTheDocument();
    },
  );

  it.each([
    ['a guest', false],
    ['a member', true],
  ])('opens a published article from ?help=article-<slug> for %s', async (_who, member) => {
    if (member) setToken('member', true);
    stubApi(homeApi());
    renderWidget('/login?help=article-bat-dau-tu-dau', <SearchProbe />);

    const panel = await screen.findByRole('dialog', { name: 'Help Center' });
    expect(
      await within(panel).findByRole('heading', { name: 'Bắt đầu từ đâu?' }),
    ).toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: 'Back' })).toBeInTheDocument();
    expect(screen.getByTestId('search')).toBeEmptyDOMElement();
  });

  it('reads "This article is no longer published" for an archived article link, leading a guest home', async () => {
    stubApi({
      ...homeApi(),
      'GET /api/v1/support/kb/articles/cach-cu': {
        status: 404,
        body: { status: 404, code: 'article-not-found' },
      },
    });
    renderWidget('/login?help=article-cach-cu');

    const panel = await screen.findByRole('dialog', { name: 'Help Center' });
    expect(
      await within(panel).findByText('This article is no longer published'),
    ).toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: 'Back to Home' })).toBeInTheDocument();
  });

  it.each(['article-Bad_Slug', 'article-', 'article--x', `article-${'a'.repeat(81)}`])(
    'ignores the malformed link %s and strips it',
    async (value) => {
      const calls = stubApi(homeApi());
      renderWidget(`/login?help=${value}`, <SearchProbe />);

      await waitFor(() => expect(screen.getByTestId('search')).toBeEmptyDOMElement());
      expect(screen.queryByRole('dialog')).toBeNull();
      expect(calls.filter((call) => call.path.includes('/kb/articles/'))).toEqual([]);
    },
  );

  it('opens Home for ?help=home without signing in', async () => {
    stubApi(homeApi());
    renderWidget('/login?help=home');

    const panel = await screen.findByRole('dialog', { name: 'Help Center' });
    expect(within(panel).getByRole('tab', { name: 'Home' })).toHaveAttribute(
      'aria-selected',
      'true',
    );
  });
});

describe('HomeTab', () => {
  it('lists the three Get started articles in the admin order and opens one in the panel', async () => {
    stubApi(homeApi());
    renderWidget('/login');
    const { user, panel } = await openPanel();

    const start = await within(panel).findByRole('list', { name: 'Get started' });
    expect(
      within(start)
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual(['Nodi làm được gì?', 'Bắt đầu từ đâu?', 'Không đăng nhập được?']);

    await user.click(within(start).getByRole('button', { name: 'Bắt đầu từ đâu?' }));

    expect(
      await within(panel).findByRole('heading', { name: 'Bắt đầu từ đâu?' }),
    ).toBeInTheDocument();
    expect(within(panel).getByText('Workflows').tagName).toBe('STRONG');
  });

  it('opens Assistant with a suggested question handed over', async () => {
    setToken('member', true);
    stubApi(homeApi());
    renderWidget('/workflows');
    const { user, panel } = await openPanel();

    await user.click(
      within(panel).getByRole('button', { name: "Why am I told I don't have enough credit?" }),
    );

    expect(within(panel).getByRole('tab', { name: 'Assistant' })).toHaveAttribute(
      'aria-selected',
      'true',
    );
    expect(
      within(panel).getByText("Why am I told I don't have enough credit?"),
    ).toBeInTheDocument();
  });

  it('shows the contact line as a mail link when a contact is configured', async () => {
    stubApi(homeApi('hotro@nodi.vn'));
    renderWidget('/login');
    const { panel } = await openPanel();

    const link = await within(panel).findByRole('link', { name: 'Email hotro@nodi.vn' });
    expect(link).toHaveAttribute('href', 'mailto:hotro@nodi.vn');
    expect(link.closest('p')).toHaveTextContent('Need help from a person? Email hotro@nodi.vn');
  });

  it.each([
    ['absent', undefined],
    ['empty', ''],
  ])('shows no contact line when the contact is %s', async (_label, contactEmail) => {
    const calls = stubApi(homeApi(contactEmail));
    const { queryClient } = renderWidget('/login');
    const { panel } = await openPanel();

    await within(panel).findByRole('list', { name: 'Get started' });
    await waitFor(() => expect(queryClient.isFetching()).toBe(0));
    expect(calls.some((call) => call.path === '/api/v1/support/settings')).toBe(true);
    expect(within(panel).queryByText(/Need help from a person/)).toBeNull();
  });

  it('gives a member the "Send a support request" shortcut to a new request', async () => {
    setToken('member', true);
    stubApi(homeApi());
    renderWidget('/workflows');
    const { user, panel } = await openPanel();

    await user.click(within(panel).getByRole('button', { name: 'Send a support request' }));

    expect(within(panel).getByRole('tab', { name: 'Requests' })).toHaveAttribute(
      'aria-selected',
      'true',
    );
    expect(within(panel).getByText('New request')).toBeInTheDocument();
  });

  it('gives a guest no request shortcut', async () => {
    stubApi(homeApi());
    renderWidget('/login');
    const { panel } = await openPanel();

    await within(panel).findByRole('list', { name: 'Get started' });
    expect(within(panel).queryByRole('button', { name: 'Send a support request' })).toBeNull();
  });

  it('reads the panel in Vietnamese once the language is switched', async () => {
    await i18n.changeLanguage('vi');
    try {
      setToken('member', true);
      stubApi(homeApi());
      renderWidget('/workflows');
      const user = userEvent.setup();
      await user.click(screen.getByRole('button', { name: 'Trợ giúp' }));
      const panel = screen.getByRole('dialog', { name: 'Trung tâm trợ giúp' });

      expect(tabNames()).toEqual(['Trang chủ', 'Trợ lý', 'Hướng dẫn', 'Yêu cầu']);
      expect(await within(panel).findByRole('list', { name: 'Bắt đầu' })).toBeInTheDocument();
      expect(
        within(panel).getByRole('button', { name: 'Vì sao tôi bị báo thiếu credit?' }),
      ).toBeInTheDocument();
      expect(within(panel).getByRole('button', { name: 'Gửi yêu cầu hỗ trợ' })).toBeInTheDocument();
    } finally {
      await i18n.changeLanguage('en');
    }
  });
});
