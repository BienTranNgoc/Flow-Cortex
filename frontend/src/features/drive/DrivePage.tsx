import { useId, useRef, type KeyboardEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router-dom';
import { PageHeading } from '../../app/PageHeading';
import { MigrationNotice } from './MigrationNotice';
import { ItemsTab } from './items/ItemsTab';
import { MemoryTab } from './memory/MemoryTab';
import './DrivePage.css';

const TABS = ['items', 'memory'] as const;
type DriveTab = (typeof TABS)[number];

/** Any value but `memory` opens Items, so a stale or hand-edited link still lands on a tab. */
function tabOf(value: string | null): DriveTab {
  return value === 'memory' ? 'memory' : 'items';
}

/** Where a member keeps what they reuse in runs: saved items and the Memory of earlier results. */
export function DrivePage() {
  const { t } = useTranslation();
  const ids = useId();
  const [params, setParams] = useSearchParams();
  const tab = tabOf(params.get('tab'));
  const tabRefs = useRef<Partial<Record<DriveTab, HTMLButtonElement | null>>>({});

  function select(next: DriveTab) {
    setParams(
      (current) => {
        const updated = new URLSearchParams(current);
        if (next === 'items') updated.delete('tab');
        else updated.set('tab', next);
        return updated;
      },
      { replace: true },
    );
  }

  function onKeyDown(event: KeyboardEvent<HTMLButtonElement>) {
    const index = TABS.indexOf(tab);
    const moves: Record<string, number> = {
      ArrowRight: (index + 1) % TABS.length,
      ArrowLeft: (index - 1 + TABS.length) % TABS.length,
      Home: 0,
      End: TABS.length - 1,
    };
    const target = moves[event.key];
    if (target === undefined) return;
    event.preventDefault();
    const next = TABS[target] as DriveTab;
    select(next);
    tabRefs.current[next]?.focus();
  }

  return (
    <div className="drive-page">
      <PageHeading title={t('drive.page.title')} />
      <MigrationNotice />
      <div role="tablist" aria-label={t('drive.tabs.label')} className="drive-page__tabs">
        {TABS.map((candidate) => (
          <button
            key={candidate}
            ref={(element) => {
              tabRefs.current[candidate] = element;
            }}
            type="button"
            role="tab"
            id={`${ids}-tab-${candidate}`}
            aria-selected={candidate === tab}
            aria-controls={candidate === tab ? `${ids}-panel` : undefined}
            tabIndex={candidate === tab ? 0 : -1}
            className="drive-page__tab"
            onClick={() => select(candidate)}
            onKeyDown={onKeyDown}
          >
            {t(`drive.tabs.${candidate}`)}
          </button>
        ))}
      </div>
      <div id={`${ids}-panel`} role="tabpanel" aria-labelledby={`${ids}-tab-${tab}`} className="drive-page__panel">
        {tab === 'memory' ? <MemoryTab /> : <ItemsTab />}
      </div>
    </div>
  );
}
