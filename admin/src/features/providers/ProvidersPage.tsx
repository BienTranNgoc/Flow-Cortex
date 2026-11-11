import { useId, useRef, useState, type KeyboardEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { PageHeading } from '../../app/PageHeading';
import { can } from '../../app/permissions';
import { usePageTitle } from '../../app/usePageTitle';
import { useMeQuery } from '../auth/useAuth';
import { ModelsTab } from './ModelsTab';
import { ProvidersTab } from './ProvidersTab';
import { useAiProviders } from './useAiProviders';
import './providers.css';

type TabId = 'models' | 'providers';
const TABS: readonly TabId[] = ['models', 'providers'];

export function ProvidersPage() {
  const { t } = useTranslation();
  usePageTitle(t('providers.title'));
  const me = useMeQuery().data;
  const canUpdate = can(me, 'ai.provider.update');
  const [active, setActive] = useState<TabId>('models');
  const baseId = useId();
  const tabRefs = useRef<Partial<Record<TabId, HTMLButtonElement | null>>>({});
  const overview = useAiProviders();
  const providerKeys = (overview.data?.categories ?? [])
    .filter((group) => group.category === 'text')
    .flatMap((group) => group.providers ?? [])
    .flatMap((provider) => (provider.key ? [provider.key] : []));

  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>, index: number) => {
    const step = { ArrowRight: 1, ArrowLeft: -1 }[event.key];
    const target =
      event.key === 'Home'
        ? 0
        : event.key === 'End'
          ? TABS.length - 1
          : step === undefined
            ? undefined
            : (index + step + TABS.length) % TABS.length;
    if (target === undefined) return;
    event.preventDefault();
    const next = TABS[target]!;
    setActive(next);
    tabRefs.current[next]?.focus();
  };

  return (
    <div className="providers-page">
      <PageHeading
        eyebrow={t('providers.eyebrow')}
        title={t('providers.title')}
        description={t('providers.description')}
      />
      <div className="providers-tabs" role="tablist" aria-label={t('providers.tabs.label')}>
        {TABS.map((tab, index) => (
          <button
            key={tab}
            ref={(node) => {
              tabRefs.current[tab] = node;
            }}
            type="button"
            role="tab"
            id={`${baseId}-tab-${tab}`}
            aria-selected={active === tab}
            aria-controls={active === tab ? `${baseId}-panel-${tab}` : undefined}
            tabIndex={active === tab ? 0 : -1}
            className="providers-tab-button"
            onClick={() => setActive(tab)}
            onKeyDown={(event) => onKeyDown(event, index)}
          >
            {t(`providers.tabs.${tab}`)}
          </button>
        ))}
      </div>
      <div
        role="tabpanel"
        id={`${baseId}-panel-${active}`}
        aria-labelledby={`${baseId}-tab-${active}`}
        className="providers-panel"
      >
        {active === 'models' ? (
          <ModelsTab canUpdate={canUpdate} providerKeys={providerKeys} />
        ) : (
          <ProvidersTab
            overview={overview.data}
            isPending={overview.isPending}
            isError={overview.isError}
            onRetry={() => overview.refetch()}
            canUpdate={canUpdate}
          />
        )}
      </div>
    </div>
  );
}
