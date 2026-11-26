import { CaretUp } from '@phosphor-icons/react';
import { useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { LegalIndexLinks } from './LegalIndexLinks';
import { useLegalIndex } from './useLegal';
import './legal.css';

/**
 * One "Legal" disclosure in the footer row; its links open in a popover above, out of flow, so the
 * footer keeps its height. Absent while the index loads, when it is empty and when its request fails.
 */
export function LegalFooterMenu() {
  const { t } = useTranslation();
  const index = useLegalIndex();
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement | null>(null);
  const triggerRef = useRef<HTMLButtonElement | null>(null);
  const panelId = useId();
  const hasEntries = (index.data?.items.length ?? 0) > 0;

  useEffect(() => {
    if (!open) return;
    const onPointer = (event: MouseEvent) => {
      const target = event.target as Node | null;
      if (target && rootRef.current?.contains(target)) return;
      setOpen(false);
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      setOpen(false);
      triggerRef.current?.focus();
    };
    document.addEventListener('mousedown', onPointer);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onPointer);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  if (!hasEntries) return null;

  return (
    <div ref={rootRef} className="legal-menu">
      <button
        ref={triggerRef}
        type="button"
        className="legal-menu__trigger"
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        onClick={() => setOpen((previous) => !previous)}
      >
        {t('legal.footer.menu')}
        <CaretUp size={14} aria-hidden="true" className="legal-menu__caret" />
      </button>
      {open && (
        <div id={panelId} className="legal-menu__panel">
          <LegalIndexLinks
            className="legal-menu__list"
            listLabel={t('legal.nav.label')}
            onNavigate={() => setOpen(false)}
          />
        </div>
      )}
    </div>
  );
}
