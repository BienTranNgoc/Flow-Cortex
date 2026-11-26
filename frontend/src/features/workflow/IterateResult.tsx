import type { RunStepResponse } from '@flow-cortex/api-client';
import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { iterateSummary } from './runResultModel';
import { stateWord } from './stepDisplay';

/** An iterate step's items: how many succeeded, then one row per item. */
export function IterateResult({ step }: Readonly<{ step: RunStepResponse }>) {
  const { t } = useTranslation();
  const summary = useMemo(() => iterateSummary(step), [step]);
  return (
    <div className="result-iterate">
      <p className="result-summary">
        {t('workflow.result.iterateSummary', {
          succeeded: summary.succeeded,
          total: summary.total,
        })}
      </p>
      <ol className="result-items">
        {summary.items.map((item) => (
          <li key={item.n} className="result-item">
            <span className="result-item-name">{t('workflow.result.item', { n: item.n })}</span>
            <span aria-hidden="true">·</span>
            <span className="result-item-state" data-state={item.state}>
              {stateWord(item.state, t)}
            </span>
            {item.text !== null && (
              <>
                <span aria-hidden="true">·</span>
                <span className="result-item-text" title={item.full ?? undefined}>
                  {item.text}
                </span>
              </>
            )}
            {item.text === null && item.files > 0 && (
              <>
                <span aria-hidden="true">·</span>
                <span>{t('workflow.result.itemFiles', { count: item.files })}</span>
              </>
            )}
          </li>
        ))}
      </ol>
    </div>
  );
}
