import type { AdminFeature, AdminSetting } from '@flow-cortex/api-client';
import { useTranslation } from 'react-i18next';
import { can } from '../../app/permissions';
import { PageHeading } from '../../app/PageHeading';
import { StatusPill } from '../../app/StatusPill';
import { usePageTitle } from '../../app/usePageTitle';
import { useMeQuery } from '../auth/useAuth';
import { SettingRow } from '../settings/SettingRow';
import { useSettingsList } from '../settings/useSettings';
import { useAdminFeatures } from './useAdminFeatures';
import '../settings/settings.css';
import './featureSwitches.css';

/** A switch the page cannot flip: an environment-only one, or a runtime one whose setting did not load. */
function ReadOnlyRow({ feature }: Readonly<{ feature: AdminFeature }>) {
  const { t } = useTranslation();
  const runtimeKey = feature.settingKey;
  const label = runtimeKey
    ? t(`settings.keys.${runtimeKey}.label`, { defaultValue: runtimeKey })
    : t(`featureSwitches.items.${feature.name}.label`, { defaultValue: feature.name });
  const help = runtimeKey
    ? t(`settings.keys.${runtimeKey}.help`, { defaultValue: '' })
    : t(`featureSwitches.items.${feature.name}.help`, { defaultValue: '' });

  return (
    <li className="cortex-admin-panel settings-item" data-feature={feature.name}>
      <div className="settings-item-head">
        <div>
          <p className="settings-item-label">{label}</p>
          {help && <p className="settings-muted">{help}</p>}
        </div>
        <StatusPill
          tone={feature.value ? 'success' : 'muted'}
          label={t(feature.value ? 'settings.value.on' : 'settings.value.off')}
        />
      </div>
      {!runtimeKey && <p className="settings-muted">{t('featureSwitches.envOnly')}</p>}
    </li>
  );
}

function Row({
  feature,
  setting,
  canUpdate,
}: Readonly<{ feature: AdminFeature; setting: AdminSetting | undefined; canUpdate: boolean }>) {
  return setting ? (
    <SettingRow setting={setting} canUpdate={canUpdate} />
  ) : (
    <ReadOnlyRow feature={feature} />
  );
}

/** Every member feature switch: a runtime one is flipped here, an environment-only one is shown. */
export function FeatureSwitchesPage() {
  const { t } = useTranslation();
  usePageTitle(t('featureSwitches.title'));
  const canUpdate = can(useMeQuery().data, 'platform.setting.update');
  const features = useAdminFeatures();
  const settings = useSettingsList();
  const items = features.data?.items;
  const settingsByKey = new Map((settings.data?.items ?? []).map((setting) => [setting.key, setting]));
  const pending = features.isPending || settings.isPending;
  const retry = () => {
    void features.refetch();
    void settings.refetch();
  };

  return (
    <main>
      <PageHeading
        eyebrow={t('featureSwitches.eyebrow')}
        title={t('featureSwitches.title')}
        description={t('featureSwitches.description')}
      />

      {pending && <output>{t('featureSwitches.loading')}</output>}
      {!pending && !items && (
        <div className="cortex-admin-error">
          <p role="alert">{t('featureSwitches.loadFailed')}</p>
          <button type="button" className="cortex-admin-btn" onClick={retry}>
            {t('featureSwitches.retry')}
          </button>
        </div>
      )}
      {!pending && items && (features.isError || settings.isError) && (
        <div className="feature-switches-notice">
          <p className="cortex-admin-pill cortex-admin-pill--warning">
            {t('featureSwitches.refreshFailed')}
          </p>
          <button type="button" className="cortex-admin-btn cortex-admin-btn--sm" onClick={retry}>
            {t('featureSwitches.retry')}
          </button>
        </div>
      )}
      {!pending && items && !canUpdate && (
        <p className="settings-muted">{t('settings.readOnly')}</p>
      )}
      {!pending && items && items.length === 0 && (
        <p className="settings-muted">{t('featureSwitches.empty')}</p>
      )}
      {!pending && items && items.length > 0 && (
        <ul className="settings-list feature-switches-list">
          {items.map((feature) => (
            <Row
              key={feature.name}
              feature={feature}
              setting={feature.settingKey ? settingsByKey.get(feature.settingKey) : undefined}
              canUpdate={canUpdate}
            />
          ))}
        </ul>
      )}
    </main>
  );
}
