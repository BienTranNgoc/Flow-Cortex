import { House } from '@phosphor-icons/react';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import i18n from '../lib/i18n';
import { SidebarNav } from './SidebarNav';

const features = vi.hoisted(() => ({ chat: false }));
vi.mock('./useFeatures', () => ({ useFeatures: () => features }));

function renderNav(path: string, rail = false) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <SidebarNav rail={rail} />
    </MemoryRouter>,
  );
}

const names = (nav: HTMLElement) =>
  within(nav)
    .getAllByRole('link')
    .map((link) => link.textContent);

describe('SidebarNav', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    features.chat = false;
  });

  it('lists Chat after Runs while the chat switch is on', () => {
    vi.stubEnv('VITE_DEMO_PAGES_ENABLED', 'false');
    features.chat = true;

    renderNav('/');

    const nav = screen.getByRole('navigation', { name: 'Main navigation' });
    expect(names(nav)).toEqual(['Home', 'Workflows', 'Runs', 'Chat']);
    expect(within(nav).getByRole('link', { name: 'Chat' })).toHaveAttribute('href', '/chat');
  });

  it('lists Home, Workflows, Runs and the Docs group while the demo pages flag is off', () => {
    vi.stubEnv('VITE_DEMO_PAGES_ENABLED', 'false');

    renderNav('/');

    const nav = screen.getByRole('navigation', { name: 'Main navigation' });
    expect(names(nav)).toEqual(['Home', 'Workflows', 'Runs']);
    expect(within(nav).getByRole('button', { name: 'Docs' })).toHaveAttribute(
      'aria-expanded',
      'false',
    );
    expect(within(nav).getByRole('link', { name: 'Home' })).toHaveAttribute('aria-current', 'page');
    expect(within(nav).getByRole('link', { name: 'Workflows' })).not.toHaveAttribute(
      'aria-current',
    );
  });

  it('marks Runs current on /runs while the demo pages flag is off', () => {
    vi.stubEnv('VITE_DEMO_PAGES_ENABLED', 'false');

    renderNav('/runs');

    const nav = screen.getByRole('navigation', { name: 'Main navigation' });
    expect(within(nav).getByRole('link', { name: 'Runs' })).toHaveAttribute('aria-current', 'page');
    expect(within(nav).getByRole('link', { name: 'Home' })).not.toHaveAttribute('aria-current');
  });

  it('adds Integrations after Runs when the flag is on, with Integrations current on /integrations', () => {
    vi.stubEnv('VITE_DEMO_PAGES_ENABLED', 'true');

    renderNav('/integrations');

    const nav = screen.getByRole('navigation', { name: 'Main navigation' });
    expect(names(nav)).toEqual(['Home', 'Workflows', 'Runs', 'Integrations']);
    expect(within(nav).getByRole('link', { name: 'Integrations' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(within(nav).getByRole('link', { name: 'Runs' })).not.toHaveAttribute('aria-current');
  });

  it('opens the Docs group on a docs sub-page and marks only that entry current', () => {
    renderNav('/docs/api');

    expect(screen.getByRole('button', { name: 'Docs' })).toHaveAttribute('aria-expanded', 'true');
    const api = screen.getByRole('link', { name: 'API Reference' });
    expect(api).toHaveAttribute('aria-current', 'page');
    const overview = screen.getByRole('link', { name: 'Overview' });
    expect(overview).not.toHaveAttribute('aria-current');
    expect(overview).toHaveAttribute('href', '/docs');
    expect(screen.getByRole('link', { name: 'Guides' })).toHaveAttribute('href', '/docs/guides');
  });

  it('marks Overview current on /docs', () => {
    renderNav('/docs');

    expect(screen.getByRole('link', { name: 'Overview' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('link', { name: 'Guides' })).not.toHaveAttribute('aria-current');
  });

  it('opens and closes the Docs group from its button', async () => {
    const user = userEvent.setup();
    renderNav('/');
    const trigger = screen.getByRole('button', { name: 'Docs' });
    expect(screen.queryByRole('link', { name: 'Overview' })).not.toBeInTheDocument();

    await user.click(trigger);
    expect(trigger).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('link', { name: 'API Reference' })).toHaveAttribute(
      'href',
      '/docs/api',
    );

    await user.click(trigger);
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('link', { name: 'Overview' })).not.toBeInTheDocument();
  });

  it('draws the current entry with the filled icon and the others regular', () => {
    renderNav('/');

    const drawing = (svg: Element | null | undefined) =>
      svg?.querySelector('path')?.getAttribute('d');
    const current = screen.getByRole('link', { name: 'Home' }).querySelector('svg');
    const { container } = render(<House size={20} weight="fill" aria-hidden="true" />);
    const regular = render(<House size={20} weight="regular" aria-hidden="true" />).container;
    expect(drawing(current)).toBe(drawing(container.querySelector('svg')));
    expect(drawing(current)).not.toBe(drawing(regular.querySelector('svg')));
    const other = screen.getByRole('link', { name: 'Workflows' }).querySelector('svg');
    expect(other).toHaveAttribute('aria-hidden', 'true');
  });

  it('keeps full accessible names in the rail, with Docs as one link', () => {
    renderNav('/workflows', true);

    const nav = screen.getByRole('navigation', { name: 'Main navigation' });
    expect(within(nav).getByRole('link', { name: 'Home' })).toBeInTheDocument();
    const workflows = within(nav).getByRole('link', { name: 'Workflows' });
    expect(workflows).toHaveAttribute('aria-current', 'page');
    expect(workflows.querySelector('.sidebar-nav__tip')).toHaveAttribute('aria-hidden', 'true');
    expect(within(nav).getByRole('link', { name: 'Docs' })).toHaveAttribute('href', '/docs');
    expect(within(nav).queryByRole('button')).not.toBeInTheDocument();
  });

  it('reaches a rail entry by keyboard and keeps its name', async () => {
    const user = userEvent.setup();
    renderNav('/', true);

    await user.tab();
    await user.tab();

    const workflows = screen.getByRole('link', { name: 'Workflows' });
    expect(workflows).toHaveFocus();
    expect(workflows).toHaveAccessibleName('Workflows');
  });

  describe('in Vietnamese', () => {
    beforeEach(async () => {
      await i18n.changeLanguage('vi');
    });
    afterEach(async () => {
      await i18n.changeLanguage('en');
    });

    it('labels the navigation, every entry and the Docs group from the locale files', () => {
      renderNav('/docs');

      const nav = screen.getByRole('navigation', { name: 'Điều hướng chính' });
      expect(within(nav).getByRole('link', { name: 'Trang chủ' })).toBeInTheDocument();
      expect(within(nav).getByRole('link', { name: 'Quy trình' })).toBeInTheDocument();
      expect(within(nav).getByRole('button', { name: 'Tài liệu' })).toBeInTheDocument();
      expect(within(nav).getByRole('link', { name: 'Tổng quan' })).toBeInTheDocument();
      expect(within(nav).getByRole('link', { name: 'Hướng dẫn' })).toBeInTheDocument();
    });
  });
});
