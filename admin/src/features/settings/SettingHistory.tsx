import type { AdminSetting } from '@flow-cortex/api-client';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { formatVietnamTime } from '../support/vietnamTime';
import { HISTORY_PAGE_SIZE, useSettingHistory } from './useSettings';

interface SettingHistoryProps {
  readonly setting: AdminSetting;
}

/** Newest-first changes of one key, loaded page by page once the disclosure is opened. */
export function SettingHistory({ setting }: Readonly<SettingHistoryProps>) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [page, setPage] = useState(0);
  const query = useSettingHistory(setting.key, page, open);
  const data = query.data;
  const panelId = `history-${setting.key}`;

  const show = (value: boolean | number | null | undefined) => {
    if (value === null || value === undefined) return t('settings.history.unset');
    if (typeof value === 'boolean') return t(value ? 'settings.value.on' : 'settings.value.off');
    return String(value);
  };

  return (
    <div className="settings-history">
      <button
        type="button"
        className="cortex-admin-btn cortex-admin-btn--ghost cortex-admin-btn--sm"
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => setOpen((value) => !value)}
      >
        {t(open ? 'settings.history.hide' : 'settings.history.show')}
      </button>
      <div id={panelId} hidden={!open}>
        {open && query.isPending && <output>{t('settings.loading')}</output>}
        {open && query.isError && !data && (
          <div className="cortex-admin-error">
            <p>{t('settings.history.loadFailed')}</p>
            <button type="button" className="cortex-admin-btn" onClick={() => query.refetch()}>
              {t('settings.retry')}
            </button>
          </div>
        )}
        {open && data && data.items.length === 0 && (
          <p className="settings-muted">{t('settings.history.empty')}</p>
        )}
        {open && data && data.items.length > 0 && (
          <>
            <ol className="settings-history-list">
              {data.items.map((change) => (
                <li key={`${change.changedAt}-${String(change.previousValue)}-${String(change.newValue)}`}>
                  <span>{formatVietnamTime(change.changedAt)}</span>
                  <span>
                    {t('settings.history.transition', {
                      from: show(change.previousValue),
                      to: show(change.newValue),
                    })}
                  </span>
                  <span className="settings-muted">
                    {change.changedBy ?? t('settings.history.unknownActor')}
                  </span>
                </li>
              ))}
            </ol>
            <div className="settings-history-pager">
              <button
                type="button"
                className="cortex-admin-btn cortex-admin-btn--ghost cortex-admin-btn--sm"
                disabled={page === 0}
                onClick={() => setPage((value) => value - 1)}
              >
                {t('settings.history.newer')}
              </button>
              <span className="settings-muted">
                {t('settings.history.range', {
                  from: page * HISTORY_PAGE_SIZE + 1,
                  to: page * HISTORY_PAGE_SIZE + data.items.length,
                  total: data.total,
                })}
              </span>
              <button
                type="button"
                className="cortex-admin-btn cortex-admin-btn--ghost cortex-admin-btn--sm"
                disabled={(page + 1) * HISTORY_PAGE_SIZE >= data.total}
                onClick={() => setPage((value) => value + 1)}
              >
                {t('settings.history.older')}
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
