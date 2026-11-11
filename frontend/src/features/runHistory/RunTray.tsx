import type { RunHistoryRowResponse } from '@flow-cortex/api-client';
import { Play } from '@phosphor-icons/react';
import { useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { formatNumber } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { formatStart, runName } from './runDisplay';
import { useRunTrayQuery } from './useRunHistory';
import './RunTray.css';

interface TrayListProps {
  readonly heading: string;
  readonly empty: string;
  readonly runs: readonly RunHistoryRowResponse[];
  readonly onNavigate: () => void;
}

function TrayList({ heading, empty, runs, onNavigate }: Readonly<TrayListProps>) {
  const { t } = useTranslation();
  const headingId = useId();
  return (
    <section className="run-tray__section" aria-labelledby={headingId}>
      <h3 id={headingId} className="run-tray__heading">
        {heading}
      </h3>
      {runs.length === 0 ? (
        <p className="run-tray__empty">{empty}</p>
      ) : (
        <ul className="run-tray__list">
          {runs.map((run) => (
            <li key={run.id}>
              <Link to={`/runs/${run.id}`} className="run-tray__item" onClick={onNavigate}>
                <span className="run-tray__name">{runName(run)}</span>
                <span className="run-tray__meta">
                  {t(`runHistory.status.${run.status}`)} · {formatStart(run.startedAt)}
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/** The top bar's Runs button: a live-run count and a panel of what runs and what finished lately. */
export function RunTray() {
  const { t } = useTranslation();
  const query = useRunTrayQuery();
  const [open, setOpen] = useState(false);
  const panelId = useId();
  const rootRef = useRef<HTMLDivElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const tray = query.data;
  const live = tray?.active.length ?? 0;

  useEffect(() => {
    if (!open) return undefined;
    const onPointer = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      setOpen(false);
      buttonRef.current?.focus();
    };
    document.addEventListener('pointerdown', onPointer);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('pointerdown', onPointer);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const close = () => setOpen(false);

  return (
    <div ref={rootRef} className="run-tray">
      <button
        ref={buttonRef}
        type="button"
        className="run-tray__button"
        aria-label={
          live > 0
            ? t('runHistory.tray.buttonWithCount', { n: formatNumber(live, activeLocale()) })
            : t('runHistory.tray.button')
        }
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        onClick={() => setOpen((value) => !value)}
      >
        <Play size={18} aria-hidden="true" />
        {live > 0 && (
          <span className="run-tray__badge" aria-hidden="true">
            {formatNumber(live, activeLocale())}
          </span>
        )}
      </button>
      {open && (
        <div id={panelId} className="run-tray__panel" aria-label={t('runHistory.tray.panelLabel')} role="group">
          {query.isError && !tray ? (
            <div className="run-tray__error" role="alert">
              <p>{t('runHistory.tray.loadError')}</p>
              <button type="button" onClick={() => void query.refetch()}>
                {t('runHistory.tray.retry')}
              </button>
            </div>
          ) : (
            <>
              <TrayList
                heading={t('runHistory.tray.running')}
                empty={t('runHistory.tray.emptyRunning')}
                runs={tray?.active ?? []}
                onNavigate={close}
              />
              <TrayList
                heading={t('runHistory.tray.finished')}
                empty={t('runHistory.tray.emptyFinished')}
                runs={tray?.recent ?? []}
                onNavigate={close}
              />
            </>
          )}
          <Link to="/runs" className="run-tray__all action-link" onClick={close}>
            {t('runHistory.tray.viewAll')}
          </Link>
        </div>
      )}
    </div>
  );
}
