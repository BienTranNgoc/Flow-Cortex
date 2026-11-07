import { NodiMark } from '@flow-cortex/ui-kit';
import { SidebarSimple, X } from '@phosphor-icons/react';
import { useCallback, useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { SidebarNav } from './SidebarNav';
import type { SidebarMode } from './sidebarMode';
import './Sidebar.css';

interface SidebarProps {
  /** At 1024px and wider the sidebar is an aside; below it, a modal drawer. */
  readonly isDesktop: boolean;
  readonly mode: SidebarMode;
  readonly onToggleMode: () => void;
  readonly drawerOpen: boolean;
  readonly onDrawerOpenChange: (open: boolean) => void;
}

function Brand({ onNavigate }: Readonly<{ onNavigate?: () => void }>) {
  const { t } = useTranslation();
  return (
    <Link to="/" className="sidebar__brand" aria-label={t('common.nav.homeLink')} onClick={onNavigate}>
      <NodiMark size={32} />
      <span className="sidebar__wordmark">{t('common.nav.brand')}</span>
    </Link>
  );
}

interface DrawerProps {
  readonly open: boolean;
  readonly onOpenChange: (open: boolean) => void;
}

function Drawer({ open, onOpenChange }: Readonly<DrawerProps>) {
  const { t } = useTranslation();
  const dialogRef = useRef<HTMLDialogElement>(null);
  const openerRef = useRef<HTMLElement | null>(null);
  const restoreFocusRef = useRef(true);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (open && !dialog.open) {
      openerRef.current =
        document.activeElement instanceof HTMLElement ? document.activeElement : null;
      restoreFocusRef.current = true;
      dialog.showModal();
      dialog.querySelector<HTMLElement>('a[href], button')?.focus();
    } else if (!open && dialog.open) {
      dialog.close();
    }
  }, [open]);

  // A drawer unmounted while open (the viewport grew) must leave the top layer; its owner moves focus.
  useEffect(() => {
    const dialog = dialogRef.current;
    return () => {
      if (dialog?.open) {
        restoreFocusRef.current = false;
        dialog.close();
      }
    };
  }, []);

  const onClose = () => {
    onOpenChange(false);
    const opener = openerRef.current;
    openerRef.current = null;
    if (!restoreFocusRef.current) return;
    if (opener?.isConnected) opener.focus();
    else document.getElementById('main')?.focus();
  };

  // Choosing an entry opens a page that takes focus itself, so the opener must not take it back.
  const onNavigate = useCallback(() => {
    restoreFocusRef.current = false;
    onOpenChange(false);
  }, [onOpenChange]);

  return (
    <dialog
      ref={dialogRef}
      className="nav-drawer"
      aria-label={t('common.nav.menu')}
      onClose={onClose}
      onClick={(event) => {
        if (event.target === event.currentTarget) dialogRef.current?.close();
      }}
    >
      <div className="nav-drawer__panel">
        <div className="nav-drawer__head">
          <Brand onNavigate={onNavigate} />
          <button
            type="button"
            className="nav-drawer__close"
            aria-label={t('common.nav.closeMenu')}
            onClick={() => dialogRef.current?.close()}
          >
            <X size={20} aria-hidden="true" />
          </button>
        </div>
        <SidebarNav rail={false} onNavigate={onNavigate} />
      </div>
    </dialog>
  );
}

export function Sidebar({
  isDesktop,
  mode,
  onToggleMode,
  drawerOpen,
  onDrawerOpenChange,
}: Readonly<SidebarProps>) {
  const { t } = useTranslation();
  if (!isDesktop) {
    return <Drawer open={drawerOpen} onOpenChange={onDrawerOpenChange} />;
  }
  return (
    <aside className="sidebar" data-mode={mode}>
      <Brand />
      <SidebarNav rail={mode === 'rail'} />
      <button
        type="button"
        className="sidebar__collapse"
        aria-label={mode === 'pinned' ? t('common.nav.collapse') : t('common.nav.expand')}
        onClick={onToggleMode}
      >
        <SidebarSimple size={20} aria-hidden="true" />
      </button>
    </aside>
  );
}
