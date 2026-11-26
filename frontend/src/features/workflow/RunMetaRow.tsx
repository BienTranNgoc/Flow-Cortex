import type { RunResponse } from '@flow-cortex/api-client';
import { useTranslation } from 'react-i18next';
import { formatCredits } from '../credits/formatCredits';
import { formatDateTime } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { runMeta } from './runResultModel';
import { formatSeconds, stepKey, stepLabel } from './stepDisplay';
import './StepResult.css';

interface RunMetaRowProps {
  run: RunResponse;
}

/** Under the run heading: where a live run is, and how a finished one went and what it cost. */
export function RunMetaRow({ run }: Readonly<RunMetaRowProps>) {
  const { t } = useTranslation();
  const locale = activeLocale();
  const steps = run.steps ?? [];
  const meta = runMeta(run);
  const current = meta.position && steps.find((step) => stepKey(step) === meta.position?.stepId);
  const facts: string[] = [];
  if (meta.position && current) {
    facts.push(
      t('workflow.runMeta.position', {
        current: meta.position.current,
        total: meta.position.total,
        name: stepLabel(current, t, steps),
      }),
    );
  }
  if (meta.finishedAt) {
    facts.push(
      formatDateTime(meta.finishedAt, locale, { dateStyle: 'medium', timeStyle: 'short' }),
    );
  }
  if (meta.durationSeconds !== null) {
    facts.push(formatSeconds(meta.durationSeconds, t, locale));
  }
  if (meta.credits !== null) {
    facts.push(
      t('workflow.runMeta.credits', {
        count: meta.credits,
        amount: formatCredits(meta.credits, locale),
      }),
    );
  }
  if (facts.length === 0) {
    return null;
  }
  return (
    <div role="group" className="run-meta-row" aria-label={t('workflow.runMeta.label')}>
      {facts.map((fact) => (
        <span key={fact}>{fact}</span>
      ))}
    </div>
  );
}
