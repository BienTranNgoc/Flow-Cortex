import {
  authHeaders,
  getAdminChatSettings,
  updateAdminChatSettings,
  type AdminChatSettings,
} from '@flow-cortex/api-client';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useId } from 'react';
import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import { usePageTitle } from '../../app/usePageTitle';
import { unwrapSupport } from '../support/unwrapSupport';
import { formatVietnamTime } from '../support/vietnamTime';
import './chat.css';

const SETTINGS_KEY = ['admin', 'chat', 'settings'] as const;

function lastChange(settings: AdminChatSettings, t: TFunction): string | null {
  if (!settings.updatedAt) return null;
  const state = t(settings.enabled ? 'chat.settings.stateOn' : 'chat.settings.stateOff');
  const at = formatVietnamTime(settings.updatedAt);
  return settings.updatedBy
    ? t('chat.settings.changedBy', { state, who: settings.updatedBy, at })
    : t('chat.settings.changedAt', { state, at });
}

export function ChatSettingsPage() {
  const { t } = useTranslation();
  usePageTitle(t('chat.settings.title'));
  const queryClient = useQueryClient();
  const toggleId = useId();
  const changeId = useId();
  const settingsQuery = useQuery({
    queryKey: SETTINGS_KEY,
    queryFn: () => unwrapSupport(getAdminChatSettings(authHeaders())),
  });
  const update = useMutation({
    mutationFn: (enabled: boolean) =>
      unwrapSupport(updateAdminChatSettings({ enabled }, authHeaders())),
    onSuccess: (settings) => queryClient.setQueryData(SETTINGS_KEY, settings),
    onError: () => queryClient.invalidateQueries({ queryKey: SETTINGS_KEY }),
  });
  const settings = settingsQuery.data;
  const change = settings ? lastChange(settings, t) : null;

  return (
    <main>
      <div className="cortex-admin-page-heading">
        <div>
          <p className="cortex-admin-eyebrow">{t('chat.settings.eyebrow')}</p>
          <h1>{t('chat.settings.title')}</h1>
          <p className="cortex-admin-page-description">{t('chat.settings.description')}</p>
        </div>
      </div>

      {settingsQuery.isPending && <output>{t('chat.common.loading')}</output>}
      {settingsQuery.isError && !settings && (
        <div className="cortex-admin-error">
          <p>{t('chat.common.loadFailed')}</p>
          <button type="button" className="cortex-admin-btn" onClick={() => settingsQuery.refetch()}>
            {t('chat.common.retry')}
          </button>
        </div>
      )}
      {settings && (
        <section className="cortex-admin-panel chat-settings">
          <div className="chat-switch">
            <input
              id={toggleId}
              type="checkbox"
              role="switch"
              checked={settings.enabled}
              aria-disabled={update.isPending}
              aria-describedby={change ? changeId : undefined}
              onChange={(event) => {
                if (!update.isPending) update.mutate(event.target.checked);
              }}
            />
            <label htmlFor={toggleId}>{t('chat.settings.toggle')}</label>
          </div>
          {change && (
            <p id={changeId} className="chat-settings-change">
              {change}
            </p>
          )}
          {update.isError && <p role="alert">{t('chat.common.saveFailed')}</p>}
        </section>
      )}
    </main>
  );
}
