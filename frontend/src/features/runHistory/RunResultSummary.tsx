import type { RunResultResponse } from '@flow-cortex/api-client';
import { useTranslation } from 'react-i18next';
import { formatNumber } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { resultSummaryView } from './runHistoryRules';

interface Props {
  /** The run's summary once it has loaded; `undefined` while loading or after a failed request. */
  readonly result: RunResultResponse | undefined;
}

/** What the run produced, beside its name: "N files" with up to three thumbnails, or "Saving files…" while registration is pending. */
export function RunResultSummary({ result }: Readonly<Props>) {
  const { t } = useTranslation();
  const view = resultSummaryView(result);

  if (view.kind === 'none') return null;
  if (view.kind === 'saving') {
    return <span className="run-result run-result__saving">{t('runHistory.row.savingFiles')}</span>;
  }
  return (
    <span className="run-result">
      {view.thumbnails.length > 0 && (
        <span className="run-result__thumbs">
          {view.thumbnails.map((url) => (
            <img key={url} className="run-result__thumb" src={url} alt="" loading="lazy" decoding="async" />
          ))}
        </span>
      )}
      <span className="run-result__count">
        {t('runHistory.row.files', { count: view.count, n: formatNumber(view.count, activeLocale()) })}
      </span>
    </span>
  );
}
