import {
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type KeyboardEvent,
  type RefObject,
} from 'react';
import { useTranslation } from 'react-i18next';

interface RowMenuProps {
  /** Accessible name of the trigger, which names the row it acts on. */
  label: string;
  pinned: boolean;
  pinBusy: boolean;
  /** Lets the row return focus here after an inline edit closes. */
  triggerRef: RefObject<HTMLButtonElement>;
  onRename: () => void;
  onTogglePin: () => void;
  onMove: () => void;
  onDelete: () => void;
}

/**
 * Disclosure menu: the trigger carries `aria-expanded`, the items are plain buttons reached with
 * Tab, Escape closes and returns focus to the trigger, and so does choosing an item.
 */
export function RowMenu({
  label,
  pinned,
  pinBusy,
  triggerRef,
  onRename,
  onTogglePin,
  onMove,
  onDelete,
}: Readonly<RowMenuProps>) {
  const { t } = useTranslation();
  const menuId = useId();
  const rootRef = useRef<HTMLDivElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const [open, setOpen] = useState(false);
  const [up, setUp] = useState(false);

  // Opens upward when the panel would run past the bottom of its scroll area or the window.
  useLayoutEffect(() => {
    const panel = panelRef.current;
    if (!open || !panel) return;
    const scroller = panel.closest('.chat-sidebar')?.getBoundingClientRect().bottom ?? Infinity;
    setUp(panel.getBoundingClientRect().bottom > Math.min(scroller, window.innerHeight));
    // The next opening is measured from the downward position again.
    return () => setUp(false);
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const onPointerDown = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener('pointerdown', onPointerDown);
    return () => document.removeEventListener('pointerdown', onPointerDown);
  }, [open]);

  const close = (restoreFocus: boolean) => {
    setOpen(false);
    if (restoreFocus) triggerRef.current?.focus();
  };

  const choose = (action: () => void) => () => {
    close(true);
    action();
  };

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape' && open) {
      event.preventDefault();
      event.stopPropagation();
      close(true);
    }
  };

  return (
    <div
      ref={rootRef}
      className="chat-menu"
      onKeyDown={onKeyDown}
      onBlur={(event) => {
        // Tabbing out closes the menu without stealing focus back. A null target (a click in a
        // browser that does not focus buttons) is left to the outside-pointer handler.
        if (open && event.relatedTarget && !rootRef.current?.contains(event.relatedTarget)) setOpen(false);
      }}
    >
      <button
        ref={triggerRef}
        type="button"
        className="chat-button chat-button--quiet chat-menu__trigger"
        aria-label={label}
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        onClick={() => setOpen((value) => !value)}
      >
        <svg aria-hidden="true" focusable="false" viewBox="0 0 4 16" className="chat-menu__dots">
          <circle cx="2" cy="2" r="1.5" />
          <circle cx="2" cy="8" r="1.5" />
          <circle cx="2" cy="14" r="1.5" />
        </svg>
      </button>
      {open && (
        <div id={menuId} ref={panelRef} className={up ? 'chat-menu__panel chat-menu__panel--up' : 'chat-menu__panel'}>
          <button type="button" className="chat-menu__item" onClick={choose(onRename)}>
            {t('chat.row.rename')}
          </button>
          <button
            type="button"
            className="chat-menu__item"
            aria-disabled={pinBusy || undefined}
            onClick={choose(() => {
              if (!pinBusy) onTogglePin();
            })}
          >
            {pinned ? t('chat.row.unpin') : t('chat.row.pin')}
          </button>
          <button type="button" className="chat-menu__item" onClick={choose(onMove)}>
            {t('chat.row.move')}
          </button>
          <button type="button" className="chat-menu__item chat-menu__item--danger" onClick={choose(onDelete)}>
            {t('chat.row.delete')}
          </button>
        </div>
      )}
    </div>
  );
}
