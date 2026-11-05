import {
  authHeaders,
  getAdminSupportSettings,
  updateAdminSupportSettings,
  type AdminSupportSettings,
} from '@flow-cortex/api-client';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useId } from 'react';
import { usePageTitle } from '../../../app/usePageTitle';
import { supportCopy } from '../supportCopy';
import { unwrapSupport } from '../unwrapSupport';
import './settings.css';

const copy = supportCopy.settings;
const SETTINGS_KEY = ['admin', 'support', 'settings'] as const;

const CONSOLE_TIME = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Asia/Ho_Chi_Minh',
  hour: '2-digit',
  minute: '2-digit',
  day: '2-digit',
  month: '2-digit',
  hourCycle: 'h23',
});

/** "HH:mm dd/MM" in Vietnam time, whatever the browser's zone. */
function consoleTime(iso: string): string {
  const parts = Object.fromEntries(
    CONSOLE_TIME.formatToParts(new Date(iso)).map((part) => [part.type, part.value]),
  );
  return `${parts.hour}:${parts.minute} ${parts.day}/${parts.month}`;
}

function lastChange(settings: AdminSupportSettings): string | null {
  if (!settings.updatedAt) return null;
  const state = settings.assistantEnabled ? copy.on : copy.off;
  const at = consoleTime(settings.updatedAt);
  return settings.updatedBy
    ? copy.changedBy(state, settings.updatedBy, at)
    : copy.changedAt(state, at);
}

export function SupportSettingsPage() {
  usePageTitle(copy.title);
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
  const change = settings ? lastChange(settings) : null;
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
          <p className="cortex-admin-eyebrow">{copy.eyebrow}</p>
          <h1>{copy.title}</h1>
          <p className="cortex-admin-page-description">{copy.description}</p>
        </div>
      </div>

      {settingsQuery.isPending && <output>{supportCopy.common.loading}</output>}
      {settingsQuery.isError && !settings && (
        <div className="cortex-admin-error">
          <p>{supportCopy.common.loadFailed}</p>
          <button type="button" className="cortex-admin-btn" onClick={() => settingsQuery.refetch()}>
            {supportCopy.common.retry}
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
            <label htmlFor={toggleId}>{copy.toggle}</label>
          </div>
          {change && (
            <p id={changeId} className="support-settings-change">
              {change}
            </p>
          )}
          {!settings.envEnabled && (
            <p id={envId} className="cortex-admin-pill cortex-admin-pill--warning">
              {copy.envDisabled}
            </p>
          )}
          {settings.ceilingReachedToday && (
            <p id={ceilingId} className="cortex-admin-pill cortex-admin-pill--warning">
              {copy.ceilingReached(settings.dailyCeiling)}
            </p>
          )}
          {update.isError && <p role="alert">{supportCopy.common.saveFailed}</p>}
        </section>
      )}
    </main>
  );
}
