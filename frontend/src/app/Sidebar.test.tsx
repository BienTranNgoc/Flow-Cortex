import { act, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import i18n from '../lib/i18n';
import { renderRoute, stubApi } from '../test/fakeApi';
import { stubMatchMedia } from '../test/matchMedia';
import { Layout } from './Layout';
import { Sidebar } from './Sidebar';
import type { SidebarMode } from './sidebarMode';

/**
 * jsdom has no `showModal`/`close`; these mirror the browser closely enough for the drawer's own
 * logic, and Escape fires `cancel` the way a browser does for an open modal dialog.
 */
function stubModalDialog() {
  const proto = HTMLDialogElement.prototype as unknown as Record<string, unknown>;
  Object.defineProperties(proto, {
    showModal: {
      configurable: true,
      value(this: HTMLDialogElement) {
        this.setAttribute('open', '');
      },
    },
    close: {
      configurable: true,
      value(this: HTMLDialogElement) {
        if (!this.hasAttribute('open')) return;
        this.removeAttribute('open');
        this.dispatchEvent(new Event('close'));
      },
    },
  });
  const onEscape = (event: KeyboardEvent) => {
    const open = document.querySelector('dialog[open]');
    if (event.key === 'Escape' && open instanceof HTMLDialogElement) {
      if (open.dispatchEvent(new Event('cancel', { cancelable: true }))) open.close();
    }
  };
  document.addEventListener('keydown', onEscape);
  return () => {
    document.removeEventListener('keydown', onEscape);
    delete proto.showModal;
    delete proto.close;
  };
}

let restoreDialog: () => void;
beforeEach(() => {
  restoreDialog = stubModalDialog();
});
afterEach(() => {
  restoreDialog();
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

const openDialog = () => document.querySelector('dialog[open]');

interface HarnessProps {
  isDesktop: boolean;
  mode?: SidebarMode;
  onToggleMode?: () => void;
}

function Harness({ isDesktop, mode = 'pinned', onToggleMode = () => {} }: Readonly<HarnessProps>) {
  const [open, setOpen] = useState(false);
  return (
    <MemoryRouter initialEntries={['/']}>
      <button type="button" onClick={() => setOpen(true)}>
        Open menu
      </button>
      <Sidebar
        isDesktop={isDesktop}
        mode={mode}
        onToggleMode={onToggleMode}
        drawerOpen={open}
        onDrawerOpenChange={setOpen}
      />
      <Routes>
        <Route path="*" element={<p>page</p>} />
      </Routes>
    </MemoryRouter>
  );
}

describe('Sidebar brand and collapse control', () => {
  it('pinned: shows the Nodi mark and wordmark linking home, and offers to collapse', async () => {
    const onToggleMode = vi.fn();
    const user = userEvent.setup();
    render(<Harness isDesktop mode="pinned" onToggleMode={onToggleMode} />);

    const brand = screen.getByRole('link', { name: 'FlowCortex home' });
    expect(brand).toHaveAttribute('href', '/');
    expect(brand.querySelector('svg.nodi-mark')).not.toBeNull();
    expect(brand).toHaveTextContent('nodi');
    expect(document.querySelector('aside.sidebar')).toHaveAttribute('data-mode', 'pinned');

    await user.click(screen.getByRole('button', { name: 'Collapse sidebar' }));
    expect(onToggleMode).toHaveBeenCalledTimes(1);
  });

  it('rail: keeps the mark linking home under the same name and offers to expand', () => {
    render(<Harness isDesktop mode="rail" />);

    expect(document.querySelector('aside.sidebar')).toHaveAttribute('data-mode', 'rail');
    const brand = screen.getByRole('link', { name: 'FlowCortex home' });
    expect(brand).toHaveAttribute('href', '/');
    expect(brand.querySelector('svg.nodi-mark')).not.toBeNull();
    expect(screen.getByRole('button', { name: 'Expand sidebar' })).toBeInTheDocument();
  });

  it('names the brand link in Vietnamese', async () => {
    await i18n.changeLanguage('vi');
    try {
      render(<Harness isDesktop mode="rail" />);

      expect(screen.getByRole('link', { name: 'Trang chủ FlowCortex' })).toHaveAttribute('href', '/');
      expect(screen.getByRole('button', { name: 'Mở rộng thanh bên' })).toBeInTheDocument();
    } finally {
      await i18n.changeLanguage('en');
    }
  });

  it('renders no dialog on desktop and no aside below it', () => {
    const { rerender } = render(<Harness isDesktop />);
    expect(document.querySelector('dialog')).toBeNull();

    rerender(<Harness isDesktop={false} />);
    expect(document.querySelector('aside')).toBeNull();
    expect(document.querySelector('dialog.nav-drawer')).not.toBeNull();
  });
});

describe('Sidebar drawer', () => {
  it('opens as a modal with focus inside, and Escape closes it back onto the menu button', async () => {
    const user = userEvent.setup();
    render(<Harness isDesktop={false} />);
    const menu = screen.getByRole('button', { name: 'Open menu' });
    expect(openDialog()).toBeNull();

    await user.click(menu);
    const dialog = openDialog();
    expect(dialog).not.toBeNull();
    expect(dialog).toContainElement(document.activeElement as HTMLElement);
    expect(within(dialog as HTMLElement).getByRole('link', { name: 'Workflows' })).toBeVisible();

    await user.keyboard('{Escape}');
    expect(openDialog()).toBeNull();
    expect(menu).toHaveFocus();
  });

  it('closes when an entry is chosen, and leaves focus for the new page', async () => {
    const user = userEvent.setup();
    render(<Harness isDesktop={false} />);
    const menu = screen.getByRole('button', { name: 'Open menu' });
    await user.click(menu);

    await user.click(within(openDialog() as HTMLElement).getByRole('link', { name: 'Workflows' }));

    expect(openDialog()).toBeNull();
    expect(menu).not.toHaveFocus();
  });

  it('closes on a click whose target is the dialog itself, not on one inside it', async () => {
    const user = userEvent.setup();
    render(<Harness isDesktop={false} />);
    await user.click(screen.getByRole('button', { name: 'Open menu' }));
    const dialog = openDialog() as HTMLElement;

    fireEvent.click(within(dialog).getByRole('navigation'));
    expect(openDialog()).not.toBeNull();

    fireEvent.click(dialog);
    expect(openDialog()).toBeNull();
  });

  it('closes from its close button', async () => {
    const user = userEvent.setup();
    render(<Harness isDesktop={false} />);
    await user.click(screen.getByRole('button', { name: 'Open menu' }));

    await user.click(within(openDialog() as HTMLElement).getByRole('button', { name: 'Close menu' }));

    expect(openDialog()).toBeNull();
  });
});

describe('Sidebar inside the shell', () => {
  it('closes the drawer and focuses the page when the viewport grows to 1024px', async () => {
    stubApi({});
    const media = stubMatchMedia(false);
    const user = userEvent.setup();
    renderRoute('/', '/', <Layout />);
    await user.click(screen.getByRole('button', { name: 'Open menu' }));
    expect(openDialog()).not.toBeNull();

    act(() => media.setMatches(true));

    expect(document.querySelector('dialog')).toBeNull();
    expect(openDialog()).toBeNull();
    expect(document.querySelector('aside.sidebar')).not.toBeNull();
    expect(screen.getByRole('main')).toHaveFocus();
    expect(screen.queryByRole('button', { name: 'Open menu' })).not.toBeInTheDocument();
  });
});
