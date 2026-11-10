import type { PaymentSettingView } from '@flow-cortex/api-client';
import { useRef, type KeyboardEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router-dom';
import { EmptyState } from '../../app/EmptyState';
import { PageHeading } from '../../app/PageHeading';
import { can } from '../../app/permissions';
import { usePageTitle } from '../../app/usePageTitle';
import { useMeQuery } from '../auth/useAuth';
import { PaymentSettingRow } from './PaymentSettingRow';
import { PaymentSettingsHistory } from './PaymentSettingsHistory';
import { usePaymentSettingsList } from './usePaymentSettings';
import './paymentSettings.css';

const TABS = ['settings', 'history'] as const;
type Tab = (typeof TABS)[number];

/** Keeps the API's order: a group's position is that of its first setting. */
function byGroup(items: readonly PaymentSettingView[]): [string, PaymentSettingView[]][] {
  const groups = new Map<string, PaymentSettingView[]>();
  for (const item of items) {
    groups.set(item.group, [...(groups.get(item.group) ?? []), item]);
  }
  return [...groups];
}

export function PaymentSettingsPage() {
  const { t } = useTranslation();
  usePageTitle(t('paymentSettings.title'));
  const canUpdate = can(useMeQuery().data, 'payment.setting.update');
  const query = usePaymentSettingsList();
  const items = query.data;
  const [params, setParams] = useSearchParams();
  const tab: Tab = params.get('tab') === 'history' ? 'history' : 'settings';
  const tabRefs = useRef<Record<Tab, HTMLButtonElement | null>>({ settings: null, history: null });

  const select = (next: Tab) => {
    setParams((current) => {
      const updated = new URLSearchParams(current);
      if (next === 'settings') updated.delete('tab');
      else updated.set('tab', next);
      return updated;
    });
  };

  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    const index = TABS.indexOf(tab);
    let next: Tab | undefined;
    if (event.key === 'ArrowRight') next = TABS[(index + 1) % TABS.length];
    else if (event.key === 'ArrowLeft') next = TABS[(index + TABS.length - 1) % TABS.length];
    else if (event.key === 'Home') next = TABS[0];
    else if (event.key === 'End') next = TABS[TABS.length - 1];
    if (!next) return;
    event.preventDefault();
    select(next);
    tabRefs.current[next]?.focus();
  };

  return (
    <div>
      <PageHeading
        eyebrow={t('paymentSettings.eyebrow')}
        title={t('paymentSettings.title')}
        description={t('paymentSettings.description')}
      />

      <div role="tablist" aria-label={t('paymentSettings.tabs.label')} className="payment-tabs">
        {TABS.map((name) => (
          <button
            key={name}
            ref={(element) => {
              tabRefs.current[name] = element;
            }}
            type="button"
            role="tab"
            id={`payment-tab-${name}`}
            aria-selected={tab === name}
            aria-controls={`payment-panel-${name}`}
            tabIndex={tab === name ? 0 : -1}
            className="payment-tab"
            onClick={() => select(name)}
            onKeyDown={onKeyDown}
          >
            {t(`paymentSettings.tabs.${name}`)}
          </button>
        ))}
      </div>

      <section
        role="tabpanel"
        id={`payment-panel-${tab}`}
        aria-labelledby={`payment-tab-${tab}`}
        className="payment-panel"
      >
        {tab === 'history' && <PaymentSettingsHistory settings={items ?? []} />}
        {tab === 'settings' && (
          <>
            {query.isPending && (
              <output className="cortex-admin-loading">{t('paymentSettings.loading')}</output>
            )}
            {query.isError && (
              <div className="cortex-admin-error">
                <p>{t('paymentSettings.loadFailed')}</p>
                <button type="button" className="cortex-admin-btn" onClick={() => query.refetch()}>
                  {t('paymentSettings.retry')}
                </button>
              </div>
            )}
            {items?.length === 0 && (
              <EmptyState
                title={t('paymentSettings.empty.title')}
                description={t('paymentSettings.empty.description')}
              />
            )}
            {items &&
              byGroup(items).map(([group, groupItems]) => (
                <section
                  key={group}
                  className="payment-group"
                  aria-labelledby={`payment-group-${group}`}
                >
                  <h2 id={`payment-group-${group}`}>
                    {t(`paymentSettings.groups.${group}`, { defaultValue: group })}
                  </h2>
                  <ul className="payment-list">
                    {groupItems.map((item) => (
                      <PaymentSettingRow key={item.key} setting={item} canUpdate={canUpdate} />
                    ))}
                  </ul>
                </section>
              ))}
          </>
        )}
      </section>
    </div>
  );
}
