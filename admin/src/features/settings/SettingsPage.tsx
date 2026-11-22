import type { AdminSetting } from '@flow-cortex/api-client';
import { useTranslation } from 'react-i18next';
import { can } from '../../app/permissions';
import { PageHeading } from '../../app/PageHeading';
import { usePageTitle } from '../../app/usePageTitle';
import { useMeQuery } from '../auth/useAuth';
import { SettingRow } from './SettingRow';
import { useSettingsList } from './useSettings';
import './settings.css';

/** Keeps the API's key order: a group's position is that of its first key. */
function byGroup(items: readonly AdminSetting[]): [string, AdminSetting[]][] {
  const groups = new Map<string, AdminSetting[]>();
  for (const item of items) {
    groups.set(item.group, [...(groups.get(item.group) ?? []), item]);
  }
  return [...groups];
}

export function SettingsPage() {
  const { t } = useTranslation();
  usePageTitle(t('settings.title'));
  const canUpdate = can(useMeQuery().data, 'platform.setting.update');
  const query = useSettingsList();
  const items = query.data?.items;

  return (
    <main>
      <PageHeading
        eyebrow={t('settings.eyebrow')}
        title={t('settings.title')}
        description={t('settings.description')}
      />

      {query.isPending && <output>{t('settings.loading')}</output>}
      {query.isError && !items && (
        <div className="cortex-admin-error">
          <p>{t('settings.loadFailed')}</p>
          <button type="button" className="cortex-admin-btn" onClick={() => query.refetch()}>
            {t('settings.retry')}
          </button>
        </div>
      )}
      {query.isError && items && (
        <p className="cortex-admin-pill cortex-admin-pill--warning">{t('settings.refreshFailed')}</p>
      )}
      {!canUpdate && items && <p className="settings-muted">{t('settings.readOnly')}</p>}
      {items && items.length === 0 && <p className="settings-muted">{t('settings.empty')}</p>}
      {items &&
        byGroup(items).map(([group, groupItems]) => (
          <section key={group} className="settings-group" aria-labelledby={`settings-group-${group}`}>
            <h2 id={`settings-group-${group}`}>
              {t(`settings.groups.${group}`, { defaultValue: group })}
            </h2>
            <ul className="settings-list">
              {groupItems.map((item) => (
                <SettingRow key={item.key} setting={item} canUpdate={canUpdate} />
              ))}
            </ul>
          </section>
        ))}
    </main>
  );
}
