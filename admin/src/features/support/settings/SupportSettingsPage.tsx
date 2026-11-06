import {
  authHeaders,
  getAdminSupportSettings,
  updateAdminSupportSettings,
  type AdminSupportSettings,
} from '@flow-cortex/api-client';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useId } from 'react';
import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import { usePageTitle } from '../../../app/usePageTitle';
import { unwrapSupport } from '../unwrapSupport';
import { formatVietnamTime } from '../vietnamTime';
import './settings.css';

const SETTINGS_KEY = ['admin', 'support', 'settings'] as const;

function lastChange(settings: AdminSupportSettings, t: TFunction): string | null {
  if (!settings.updatedAt) return null;
  const state = t(
    settings.assistantEnabled ? 'support.settings.stateOn' : 'support.settings.stateOff',
  );
  const at = formatVietnamTime(settings.updatedAt);
  return settings.updatedBy
    ? t('support.settings.changedBy', { state, email: settings.updatedBy, at })
    : t('support.settings.changedAt', { state, at });
}

export function SupportSettingsPage() {
  const { t } = useTranslation();
  usePageTitle(t('support.settings.title'));
  const queryClient = useQueryClient();
  const toggleId = useId();
  const changeId = useId();
  const envId = useId();
  const ceilingId = useId();
  const settingsQuery = useQuery({
    queryKey: SETTINGS_KEY,
    queryFn: () => unwrapSupport(getAdminSupportSettings(authHeaders())),
  });
  const update = useMutation({
    mutationFn: (assistantEnabled: boolean) =>
      unwrapSupport(updateAdminSupportSettings({ assistantEnabled }, authHeaders())),
    onSuccess: (settings) => queryClient.setQueryData(SETTINGS_KEY, settings),
    onError: () => queryClient.invalidateQueries({ queryKey: SETTINGS_KEY }),
  });
  const settings = settingsQuery.data;
  const change = settings ? lastChange(settings, t) : null;
  const describedBy = settings
    ? [
        change ? changeId : undefined,
        settings.envEnabled ? undefined : envId,
        settings.ceilingReachedToday ? ceilingId : undefined,
      ]
        .filter(Boolean)
        .join(' ') || undefined
    : undefined;

  return (
    <main>
      <div className="cortex-admin-page-heading">
        <div>
          <p className="cortex-admin-eyebrow">{t('support.settings.eyebrow')}</p>
          <h1>{t('support.settings.title')}</h1>
          <p className="cortex-admin-page-description">{t('support.settings.description')}</p>
        </div>
      </div>

      {settingsQuery.isPending && <output>{t('support.common.loading')}</output>}
      {settingsQuery.isError && !settings && (
        <div className="cortex-admin-error">
          <p>{t('support.common.loadFailed')}</p>
          <button type="button" className="cortex-admin-btn" onClick={() => settingsQuery.refetch()}>
            {t('support.common.retry')}
          </button>
        </div>
      )}
      {settings && (
        <section className="cortex-admin-panel support-settings">
          <div className="support-switch">
            <input
              id={toggleId}
              type="checkbox"
              role="switch"
              checked={settings.assistantEnabled}
              aria-disabled={update.isPending}
              aria-describedby={describedBy}
              onChange={(event) => {
                if (!update.isPending) update.mutate(event.target.checked);
              }}
            />
            <label htmlFor={toggleId}>{t('support.settings.toggle')}</label>
          </div>
          {change && (
            <p id={changeId} className="support-settings-change">
              {change}
            </p>
          )}
          {!settings.envEnabled && (
            <p id={envId} className="cortex-admin-pill cortex-admin-pill--warning">
              {t('support.settings.envDisabled')}
            </p>
          )}
          {settings.ceilingReachedToday && (
            <p id={ceilingId} className="cortex-admin-pill cortex-admin-pill--warning">
              {t('support.settings.ceilingReached', { ceiling: settings.dailyCeiling })}
            </p>
          )}
          {update.isError && <p role="alert">{t('support.common.saveFailed')}</p>}
        </section>
      )}
    </main>
  );
}
