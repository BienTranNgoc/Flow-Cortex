import type { CatalogCapabilityList } from '@flow-cortex/api-client';
import { useId, useRef, useState, type KeyboardEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { EmptyState } from '../../app/EmptyState';
import { PageHeading } from '../../app/PageHeading';
import { StatusPill } from '../../app/StatusPill';
import { TableRegion } from '../../app/TableRegion';
import { usePageTitle } from '../../app/usePageTitle';
import { DescriptorsView } from './DescriptorsView';
import { useCapabilities } from './useModelCatalog';
import './modelCatalog.css';

type TabId = 'capabilities' | 'descriptors';
const TABS: readonly TabId[] = ['capabilities', 'descriptors'];

/** The server's `uncatalogued` list: registered engine types that have no catalog row yet. */
function UncataloguedNotice({ keys }: Readonly<{ keys: readonly string[] }>) {
  const { t } = useTranslation();
  if (keys.length === 0) return null;
  return (
    <div className="cortex-admin-disabled-banner" role="status">
      <strong>{t('modelCatalog.capabilities.uncatalogued.title', { count: keys.length })}</strong>{' '}
      {t('modelCatalog.capabilities.uncatalogued.body')} <code>{keys.join(', ')}</code>
    </div>
  );
}

function CapabilitiesTab({
  list,
  isPending,
  isError,
  onRetry,
}: Readonly<{
  list: CatalogCapabilityList | undefined;
  isPending: boolean;
  isError: boolean;
  onRetry: () => void;
}>) {
  const { t } = useTranslation();
  const columns = {
    capability: t('modelCatalog.capabilities.columns.capability'),
    category: t('modelCatalog.capabilities.columns.category'),
    group: t('modelCatalog.capabilities.columns.group'),
    models: t('modelCatalog.capabilities.columns.models'),
    engine: t('modelCatalog.capabilities.columns.engine'),
  };

  return (
    <section className="model-catalog-tab" aria-label={t('modelCatalog.tabs.capabilities')}>
      {isError && (
        <div className="cortex-admin-error">
          <p role="alert">{t('modelCatalog.loadError')}</p>
          <button type="button" className="cortex-admin-btn" onClick={onRetry}>
            {t('modelCatalog.retry')}
          </button>
        </div>
      )}
      {isPending && <output className="cortex-admin-loading">{t('modelCatalog.loading')}</output>}
      {list && <UncataloguedNotice keys={list.uncatalogued} />}
      {list?.capabilities.length === 0 && (
        <EmptyState
          title={t('modelCatalog.capabilities.empty')}
          description={t('modelCatalog.capabilities.emptyHint')}
        />
      )}
      {list && list.capabilities.length > 0 && (
        <TableRegion label={t('modelCatalog.capabilities.tableLabel')}>
          <table className="cortex-admin-table model-catalog-table">
            <thead>
              <tr>
                <th scope="col">{columns.capability}</th>
                <th scope="col" className="model-catalog-col-extra">
                  {columns.category}
                </th>
                <th scope="col" className="model-catalog-col-extra">
                  {columns.group}
                </th>
                <th scope="col">{columns.models}</th>
                <th scope="col" className="model-catalog-col-extra">
                  {columns.engine}
                </th>
              </tr>
            </thead>
            <tbody>
              {list.capabilities.map((row) => (
                <tr key={row.capability}>
                  <td data-label={columns.capability}>
                    <Link
                      className="cortex-admin-link"
                      to={`/model-catalog/${encodeURIComponent(row.capability)}`}
                    >
                      <code>{row.capability}</code>
                    </Link>
                  </td>
                  <td data-label={columns.category} className="model-catalog-col-extra">
                    {t(`modelCatalog.categories.${row.category}`, { defaultValue: row.category })}
                  </td>
                  <td data-label={columns.group} className="model-catalog-col-extra">
                    {t(`modelCatalog.groups.${row.uiGroup}`, { defaultValue: row.uiGroup })}
                  </td>
                  <td data-label={columns.models}>
                    {t('modelCatalog.capabilities.modelsValue', {
                      total: row.modelCount,
                      enabled: row.enabledModelCount,
                    })}
                  </td>
                  <td data-label={columns.engine} className="model-catalog-col-extra">
                    <StatusPill
                      tone={row.registered ? 'success' : 'warning'}
                      label={t(
                        row.registered
                          ? 'modelCatalog.capabilities.registered'
                          : 'modelCatalog.capabilities.notRegistered',
                      )}
                    />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </TableRegion>
      )}
    </section>
  );
}

/** `/model-catalog`: the capability catalog and the shipped model descriptors. */
export function ModelCatalogPage() {
  const { t } = useTranslation();
  usePageTitle(t('modelCatalog.title'));
  const [active, setActive] = useState<TabId>('capabilities');
  const baseId = useId();
  const tabRefs = useRef<Partial<Record<TabId, HTMLButtonElement | null>>>({});
  const capabilities = useCapabilities();

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
    <div className="model-catalog-page">
      <PageHeading
        eyebrow={t('modelCatalog.eyebrow')}
        title={t('modelCatalog.title')}
        description={t('modelCatalog.description')}
      />
      <div className="model-catalog-tabs" role="tablist" aria-label={t('modelCatalog.tabs.label')}>
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
            className="model-catalog-tab-button"
            onClick={() => setActive(tab)}
            onKeyDown={(event) => onKeyDown(event, index)}
          >
            {t(`modelCatalog.tabs.${tab}`)}
          </button>
        ))}
      </div>
      <div
        role="tabpanel"
        id={`${baseId}-panel-${active}`}
        aria-labelledby={`${baseId}-tab-${active}`}
        className="model-catalog-panel"
      >
        {active === 'capabilities' ? (
          <CapabilitiesTab
            list={capabilities.data}
            isPending={capabilities.isPending}
            isError={capabilities.isError}
            onRetry={() => capabilities.refetch()}
          />
        ) : (
          <DescriptorsView />
        )}
      </div>
    </div>
  );
}
