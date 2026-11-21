import { ApiError } from '@flow-cortex/api-client';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { downloadCsv } from '../lib/downloadCsv';
import { formatNumber } from '../lib/format';
import { activeLocale } from '../lib/i18n';

/** Rows the server puts in one export file. */
export const EXPORT_ROW_CAP = 1000;

interface ExportCsvButtonProps {
  /** The export URL with the list's current filters. */
  url: string;
  /** Key of the lower-case noun with `_one`/`_other` forms, used to say how many rows the file holds. */
  nounKey: string;
  disabled?: boolean;
}

type Outcome =
  | { kind: 'done'; fileName: string; total: number | undefined }
  | { kind: 'error'; key: string };

function failureKey(error: unknown): string {
  if (error instanceof ApiError && error.status === 403) return 'common.shell.export.forbidden';
  if (error instanceof ApiError && error.status === 503) return 'common.shell.export.disabled';
  return 'common.shell.export.failed';
}

/** Downloads the export and says whether the file holds every matching row or only the newest ones. */
export function ExportCsvButton({ url, nounKey, disabled = false }: Readonly<ExportCsvButtonProps>) {
  const { t } = useTranslation();
  const [working, setWorking] = useState(false);
  const [outcome, setOutcome] = useState<Outcome | undefined>();

  const start = async () => {
    setWorking(true);
    setOutcome(undefined);
    try {
      const { fileName, total } = await downloadCsv(url);
      setOutcome({ kind: 'done', fileName, total });
    } catch (error) {
      setOutcome({ kind: 'error', key: failureKey(error) });
    } finally {
      setWorking(false);
    }
  };

  const locale = activeLocale();
  let message: string | undefined;
  if (outcome?.kind === 'error') {
    message = t(outcome.key);
  } else if (outcome?.kind === 'done') {
    message =
      outcome.total !== undefined && outcome.total > EXPORT_ROW_CAP
        ? t('common.shell.export.capped', {
            shown: formatNumber(EXPORT_ROW_CAP, locale),
            total: formatNumber(outcome.total, locale),
            noun: t(nounKey, { count: outcome.total }),
          })
        : t('common.shell.export.done', { fileName: outcome.fileName });
  }

  return (
    <>
      <button
        type="button"
        className="cortex-admin-btn"
        disabled={disabled || working}
        onClick={() => void start()}
      >
        {working ? t('common.shell.export.working') : t('common.shell.export.button')}
      </button>
      {message && (
        <p
          className={outcome?.kind === 'error' ? 'cortex-admin-form-error' : 'cortex-admin-stale'}
          role={outcome?.kind === 'error' ? 'alert' : 'status'}
        >
          {message}
        </p>
      )}
    </>
  );
}
