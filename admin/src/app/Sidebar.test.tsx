import { setToken } from '@flow-cortex/api-client';
import { Lifebuoy, SquaresFour } from '@phosphor-icons/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router-dom';
import { App } from '../App';
import i18n from '../lib/i18n';
import viCommon from '../locales/vi/common.json';
import { ADMIN_ME, MEMBER_ME, stubApi, testQueryClient } from '../test/fakeApi';
import { stubMatchMedia } from '../test/matchMedia';
import type { NavGroup } from './nav';
import type { Caller } from './permissions';
import { queryClient } from './queryClient';
import { Sidebar } from './Sidebar';
import { useLiveNav } from './useLiveNav';

const OPEN_COUNT_PATH = '/api/v1/admin/support/requests/open-count';

function LiveSidebar({ me, collapsed }: Readonly<{ me: Caller; collapsed: boolean }>) {
  return <Sidebar me={me} collapsed={collapsed} onToggle={() => {}} nav={useLiveNav(me)} />;
}

function renderSidebar(me: Caller, collapsed = false) {
  return render(
    <QueryClientProvider client={testQueryClient()}>
      <MemoryRouter>
        <LiveSidebar me={me} collapsed={collapsed} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

afterEach(() => {
  setToken(null);
  queryClient.clear();
  vi.unstubAllGlobals();
});

describe('Sidebar', () => {
  it('links dashboard and permitted entries, and shows disabled items as inert text', () => {
    stubApi({ [`GET ${OPEN_COUNT_PATH}`]: { status: 200, body: { count: 0 } } });
    renderSidebar(ADMIN_ME);

    expect(screen.getByRole('link', { name: /Dashboard/ })).toHaveAttribute('href', '/dashboard');
    expect(screen.getByRole('link', { name: /Runs/ })).toHaveAttribute('href', '/runs');
    expect(screen.getByRole('link', { name: /Media Jobs/i })).toHaveAttribute('href', '/media-jobs');
    expect(screen.getByRole('link', { name: /Users/ })).toHaveAttribute('href', '/users');
    expect(screen.getByRole('link', { name: /SMS Outbox/ })).toHaveAttribute('href', '/sms-outbox');

    const voice = screen.getByText('Voice Quota').closest('[aria-disabled]');
    expect(voice).toHaveAttribute('aria-disabled', 'true');
    expect(voice).toHaveTextContent('Coming soon');
    expect(voice?.querySelector('.cortex-admin-nav__visually-hidden')).toHaveTextContent(
      'unavailable',
    );
    expect(voice?.closest('a')).toBeNull();
    expect(screen.queryByRole('link', { name: /Voice Quota/ })).not.toBeInTheDocument();
  });

  it.each([
    'Workflows & Templates',
    'Approval Queue',
    'Credits & Wallets',
    'Model Providers',
    'Feature Switches',
    'Audit Log',
    'Voice Quota',
    'Health',
  ])(
    'shows %s as a disabled "Coming soon" entry, never a link',
    (label) => {
      stubApi({ [`GET ${OPEN_COUNT_PATH}`]: { status: 200, body: { count: 0 } } });
      renderSidebar(ADMIN_ME);

      const entry = screen.getByText(label).closest('[aria-disabled]');
      expect(entry).toHaveAttribute('aria-disabled', 'true');
      expect(entry).toHaveTextContent('Coming soon');
      expect(screen.queryByRole('link', { name: new RegExp(label, 'i') })).not.toBeInTheDocument();
    },
  );

  it('links no entry for a caller holding no key and reads no open count', () => {
    const calls = stubApi({});
    renderSidebar(MEMBER_ME);

    expect(screen.queryAllByRole('link')).toHaveLength(0);
    expect(calls).toHaveLength(0);
  });

  it('links Admin accounts in the System group only with system.admin_account.view', () => {
    stubApi({ [`GET ${OPEN_COUNT_PATH}`]: { status: 200, body: { count: 0 } } });
    const { unmount } = renderSidebar(ADMIN_ME);
    expect(screen.getByRole('link', { name: 'Admin accounts' })).toHaveAttribute(
      'href',
      '/admin-accounts',
    );
    unmount();

    renderSidebar({ ...ADMIN_ME, permissions: ['user.account.view'] });
    expect(screen.queryByRole('link', { name: 'Admin accounts' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Users' })).toBeInTheDocument();
  });

  it('groups the Help Center pages under "Support" with the open request count on Requests', async () => {
    stubApi({ [`GET ${OPEN_COUNT_PATH}`]: { status: 200, body: { count: 4 } } });
    renderSidebar(ADMIN_ME);

    const group = screen.getByRole('list', { name: 'Support' });
    expect(group).toContainElement(screen.getByRole('link', { name: 'Guide articles' }));
    expect(screen.getByRole('link', { name: 'Topics' })).toHaveAttribute('href', '/support/groups');
    expect(screen.getByRole('link', { name: 'Assistant settings' })).toHaveAttribute(
      'href',
      '/support/settings',
    );
    const requests = await screen.findByRole('link', { name: 'Requests, 4 open requests' });
    expect(requests).toHaveAttribute('href', '/support/requests');
    expect(requests).toHaveTextContent('4');
  });

  it('names a single open request in the singular', async () => {
    stubApi({ [`GET ${OPEN_COUNT_PATH}`]: { status: 200, body: { count: 1 } } });
    renderSidebar(ADMIN_ME);

    expect(await screen.findByRole('link', { name: 'Requests, 1 open request' })).toBeInTheDocument();
  });

  it('shows no count on Requests while nothing is open', async () => {
    const calls = stubApi({ [`GET ${OPEN_COUNT_PATH}`]: { status: 200, body: { count: 0 } } });
    renderSidebar(ADMIN_ME);

    await vi.waitFor(() => expect(calls.some((call) => call.path === OPEN_COUNT_PATH)).toBe(true));
    expect(screen.getByRole('link', { name: 'Requests' })).not.toHaveTextContent(/\d/);
  });

  it('falls back to Dashboard when the address bar names a disabled entry', async () => {
    stubApi({
      'GET /api/v1/auth/me': { status: 200, body: ADMIN_ME },
      'GET /api/v1/admin/runs': { status: 200, body: { items: [], page: 0, size: 20, total: 0 } },
      'GET /api/v1/users/stats': { status: 200, body: {} },
      [`GET ${OPEN_COUNT_PATH}`]: { status: 200, body: { count: 0 } },
    });
    setToken('admin-token-voice', true);
    window.history.pushState({}, '', '/voice-quota');
    render(<App />);

    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/dashboard');
  });

  it('switches between expanded and icon-only mode', async () => {
    stubApi({ [`GET ${OPEN_COUNT_PATH}`]: { status: 200, body: { count: 0 } } });
    const { container } = renderSidebar(ADMIN_ME);
    expect(container.querySelector('.cortex-admin-nav')).not.toHaveClass(
      'cortex-admin-nav--collapsed',
    );
    expect(screen.getByText('Dashboard')).toBeVisible();

    renderSidebar(ADMIN_ME, true);
    expect(screen.getAllByRole('link').length).toBeGreaterThan(0);
  });

  it('hosts the collapse control inside the sidebar and reports its state with aria-pressed', async () => {
    const onToggle = vi.fn();
    const { rerender } = render(
      <MemoryRouter>
        <Sidebar me={ADMIN_ME} collapsed={false} onToggle={onToggle} />
      </MemoryRouter>,
    );
    const nav = screen.getByRole('navigation', { name: 'Admin navigation' });
    const aside = nav.closest('aside');
    const collapse = within(aside as HTMLElement).getByRole('button', { name: 'Collapse sidebar' });
    expect(collapse).toHaveAttribute('aria-pressed', 'false');
    expect(collapse.querySelector('svg')).toHaveAttribute('aria-hidden', 'true');

    await userEvent.click(collapse);
    expect(onToggle).toHaveBeenCalledTimes(1);

    rerender(
      <MemoryRouter>
        <Sidebar me={ADMIN_ME} collapsed onToggle={onToggle} />
      </MemoryRouter>,
    );
    expect(screen.getByRole('button', { name: 'Expand sidebar' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
  });

  it('shows the Nodi mark, the wordmark and the Admin label as the sidebar brand', () => {
    render(
      <MemoryRouter>
        <Sidebar me={ADMIN_ME} collapsed={false} onToggle={() => {}} />
      </MemoryRouter>,
    );
    const brand = screen.getByText('nodi').parentElement as HTMLElement;
    expect(brand.querySelector('svg.nodi-mark')).not.toBeNull();
    expect(within(brand).getByText('Admin')).toBeInTheDocument();
  });

  it('marks the current entry and draws its icon filled, the others regular', () => {
    render(
      <MemoryRouter initialEntries={['/dashboard']}>
        <Sidebar me={ADMIN_ME} collapsed={false} onToggle={() => {}} />
      </MemoryRouter>,
    );
    const current = screen.getByRole('link', { name: /Dashboard/ });
    expect(current).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('link', { name: /Runs/ })).not.toHaveAttribute('aria-current');

    const filled = renderToStaticMarkup(<SquaresFour weight="fill" />);
    const regular = renderToStaticMarkup(<SquaresFour weight="regular" />);
    const markup = current.querySelector('svg')?.innerHTML ?? '';
    expect(markup).not.toBe('');
    expect(filled).toContain(markup);
    expect(regular).not.toContain(markup);
  });

  it('carries no native title tooltip on any entry, expanded or in the rail', () => {
    for (const collapsed of [false, true]) {
      const { container, unmount } = render(
        <MemoryRouter>
          <Sidebar me={ADMIN_ME} collapsed={collapsed} onToggle={() => {}} />
        </MemoryRouter>,
      );
      expect(container.querySelectorAll('[title]')).toHaveLength(0);
      unmount();
    }
  });

  it('gives a disabled rail entry a tooltip that names its coming-soon tag, and no link', () => {
    render(
      <MemoryRouter>
        <Sidebar me={ADMIN_ME} collapsed onToggle={() => {}} />
      </MemoryRouter>,
    );
    const entry = screen.getByText('Voice Quota').closest('[aria-disabled]');
    expect(entry).toHaveAttribute('aria-disabled', 'true');
    expect(entry?.closest('a')).toBeNull();
    const tip = (entry as HTMLElement).querySelector('.cortex-admin-nav__tip');
    expect(tip).toHaveAttribute('data-tip', 'Voice Quota — Coming soon');
    expect(tip).toHaveAttribute('aria-hidden', 'true');
  });

  it('places a rail tooltip at the hovered or focused entry, so it can escape the scrolling list', () => {
    render(
      <MemoryRouter>
        <Sidebar me={ADMIN_ME} collapsed onToggle={() => {}} />
      </MemoryRouter>,
    );
    const link = screen.getByRole('link', { name: /Runs/ });
    const tip = link.querySelector<HTMLElement>('.cortex-admin-nav__tip') as HTMLElement;
    expect(tip.style.top).toBe('');
    vi.spyOn(link, 'getBoundingClientRect').mockReturnValue({ top: 200, height: 44 } as DOMRect);

    fireEvent.focus(link);
    expect(tip.style.top).toBe('222px');

    tip.style.top = '';
    fireEvent.mouseEnter(link);
    expect(tip.style.top).toBe('222px');
  });

  it('lets the keyboard reach a disabled rail entry, shows its tooltip there, and keeps it inert', async () => {
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <Sidebar me={ADMIN_ME} collapsed onToggle={() => {}} />
      </MemoryRouter>,
    );
    const entry = screen.getByText('Voice Quota').closest('[aria-disabled]') as HTMLElement;
    expect(entry).toHaveAttribute('tabindex', '0');
    const tip = entry.querySelector<HTMLElement>('.cortex-admin-nav__tip') as HTMLElement;
    vi.spyOn(entry, 'getBoundingClientRect').mockReturnValue({ top: 300, height: 44 } as DOMRect);

    await user.tab();
    while (document.activeElement !== entry && document.activeElement !== document.body) {
      await user.tab();
    }
    expect(entry).toHaveFocus();
    expect(tip.style.top).toBe('322px');

    await user.keyboard('{Enter}');
    await user.click(entry);
    expect(entry).toHaveAttribute('aria-disabled', 'true');
    expect(screen.queryByRole('link', { name: /Voice Quota/ })).not.toBeInTheDocument();
  });

  it('follows the entry with the open rail tooltip when the list scrolls', () => {
    const { container } = render(
      <MemoryRouter>
        <Sidebar me={ADMIN_ME} collapsed onToggle={() => {}} />
      </MemoryRouter>,
    );
    const link = screen.getByRole('link', { name: /Runs/ });
    const tip = link.querySelector<HTMLElement>('.cortex-admin-nav__tip') as HTMLElement;
    const rect = vi.spyOn(link, 'getBoundingClientRect');
    rect.mockReturnValue({ top: 200, height: 44 } as DOMRect);
    fireEvent.focus(link);
    expect(tip.style.top).toBe('222px');

    rect.mockReturnValue({ top: 150, height: 44 } as DOMRect);
    fireEvent.scroll(container.querySelector('.cortex-admin-nav__groups') as HTMLElement);
    expect(tip.style.top).toBe('172px');
  });

  describe('with a count', () => {
    const SUPPORT: NavGroup[] = [
      {
        id: 'support',
        label: 'common.shell.nav.groups.overview',
        items: [
          {
            label: 'Support',
            path: '/support',
            icon: Lifebuoy,
            key: 'workflow.run.view',
            badge: 3,
          },
          { label: 'Empty', path: '/empty', icon: Lifebuoy, key: 'workflow.run.view', badge: 0 },
          { label: 'Plain', path: '/plain', icon: Lifebuoy, key: 'workflow.run.view' },
        ],
      },
    ];

    it('shows the number beside an expanded label and reads it as part of the name', () => {
      render(
        <MemoryRouter>
          <Sidebar me={ADMIN_ME} collapsed={false} onToggle={() => {}} nav={SUPPORT} />
        </MemoryRouter>,
      );
      const link = screen.getByRole('link', { name: 'Support, 3' });
      expect(within(link).getByText('3')).toBeInTheDocument();
    });

    it('marks the rail icon and words the tooltip and the name as "label, count"', () => {
      render(
        <MemoryRouter>
          <Sidebar me={ADMIN_ME} collapsed onToggle={() => {}} nav={SUPPORT} />
        </MemoryRouter>,
      );
      const link = screen.getByRole('link', { name: 'Support, 3' });
      expect(link.querySelector('.cortex-admin-nav__badge')).not.toBeNull();
      const tip = link.querySelector('.cortex-admin-nav__tip');
      expect(tip).toHaveAttribute('data-tip', 'Support, 3');
      expect(tip).toHaveAttribute('aria-hidden', 'true');
    });

    it('shows nothing for a zero or an absent count', () => {
      const { container } = render(
        <MemoryRouter>
          <Sidebar me={ADMIN_ME} collapsed={false} onToggle={() => {}} nav={SUPPORT} />
        </MemoryRouter>,
      );
      for (const name of ['Empty', 'Plain']) {
        const link = screen.getByRole('link', { name });
        expect(link.querySelector('.cortex-admin-nav__badge')).toBeNull();
        expect(link).not.toHaveAttribute('aria-label');
      }
      expect(container.querySelectorAll('.cortex-admin-nav__badge')).toHaveLength(1);
    });
  });

  describe('as a drawer below 1024px', () => {
    const showModalDescriptor = Object.getOwnPropertyDescriptor(
      HTMLDialogElement.prototype,
      'showModal',
    );
    const closeDescriptor = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'close');

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
      for (const [name, descriptor] of [
        ['showModal', showModalDescriptor],
        ['close', closeDescriptor],
      ] as const) {
        if (descriptor) {
          Object.defineProperty(HTMLDialogElement.prototype, name, descriptor);
        } else {
          Reflect.deleteProperty(HTMLDialogElement.prototype, name);
        }
      }
    });

    it('opens as a modal dialog holding the full navigation, with no collapse control', () => {
      stubMatchMedia(false);
      render(
        <MemoryRouter>
          <Sidebar
            me={ADMIN_ME}
            collapsed
            onToggle={() => {}}
            drawerOpen
            onDrawerClose={() => {}}
          />
        </MemoryRouter>,
      );
      const dialog = screen.getByRole('dialog', { name: 'Admin navigation' });
      expect(dialog).toHaveAttribute('open');
      expect(within(dialog).getByRole('link', { name: /Users/ })).toBeInTheDocument();
      expect(within(dialog).getByRole('button', { name: 'Close menu' })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /sidebar/ })).not.toBeInTheDocument();
      expect(dialog).not.toHaveClass('cortex-admin-nav--collapsed');
    });

    it('renders an aside, not a dialog, once the viewport reaches 1024px', () => {
      stubMatchMedia(true);
      const { container } = render(
        <MemoryRouter>
          <Sidebar
            me={ADMIN_ME}
            collapsed={false}
            onToggle={() => {}}
            drawerOpen
            onDrawerClose={() => {}}
          />
        </MemoryRouter>,
      );
      expect(container.querySelector('dialog')).toBeNull();
      expect(container.querySelector('aside')).not.toBeNull();
    });

    it('closes on choosing an entry, on Escape and on a backdrop click, not on an inner click', async () => {
      stubMatchMedia(false);
      const onDrawerClose = vi.fn();
      render(
        <MemoryRouter>
          <Sidebar
            me={ADMIN_ME}
            collapsed={false}
            onToggle={() => {}}
            drawerOpen
            onDrawerClose={onDrawerClose}
          />
        </MemoryRouter>,
      );
      const dialog = screen.getByRole('dialog', { name: 'Admin navigation' });

      await userEvent.click(within(dialog).getByText('Users'));
      expect(dialog).not.toHaveAttribute('open');
      expect(onDrawerClose).toHaveBeenCalledTimes(1);

      dialog.setAttribute('open', '');
      fireEvent.click(within(dialog).getByRole('navigation'));
      expect(dialog).toHaveAttribute('open');

      fireEvent.click(dialog);
      expect(dialog).not.toHaveAttribute('open');
      expect(onDrawerClose).toHaveBeenCalledTimes(2);

      dialog.setAttribute('open', '');
      fireEvent(dialog, new Event('close'));
      expect(onDrawerClose).toHaveBeenCalledTimes(3);
    });
  });

  describe('in Vietnamese', () => {
    beforeEach(async () => {
      await i18n.changeLanguage('vi');
    });
    afterEach(async () => {
      await i18n.changeLanguage('en');
    });

    it('labels the groups, links, disabled entries and system status in Vietnamese', () => {
      stubApi({ [`GET ${OPEN_COUNT_PATH}`]: { status: 200, body: { count: 0 } } });
      renderSidebar(ADMIN_ME);
      const { nav } = viCommon.shell;

      expect(screen.getByRole('navigation', { name: nav.label })).toBeInTheDocument();
      expect(screen.getByText(nav.groups.workflowOps)).toBeInTheDocument();
      expect(screen.getByRole('link', { name: nav.items.runs })).toHaveAttribute('href', '/runs');
      expect(screen.getByRole('link', { name: nav.items.mediaJobs })).toHaveAttribute(
        'href',
        '/media-jobs',
      );

      const voice = screen.getByText(nav.items.voiceQuota).closest('[aria-disabled]');
      expect(voice).toHaveTextContent(nav.comingSoon);
      expect(voice).not.toHaveAttribute('title');
      expect(voice?.querySelector('.cortex-admin-nav__tip')).toHaveAttribute(
        'data-tip',
        `${nav.items.voiceQuota} — ${nav.comingSoon}`,
      );
      expect(voice?.querySelector('.cortex-admin-nav__visually-hidden')).toHaveTextContent(
        nav.unavailable,
      );
      expect(screen.getByText(nav.systemStatus)).toBeInTheDocument();
    });
  });
});
