import type { RunStepResponse } from '@flow-cortex/api-client';
import { Clock } from '@phosphor-icons/react';
import type { ReactNode, RefObject } from 'react';
import { useTranslation } from 'react-i18next';
import { PageHeading } from '../../app/PageHeading';
import { StatusGlyph } from './stepGlyphs';
import { useElapsedClock } from './useElapsedClock';

interface ProductVideoRunHeaderProps {
  title: string;
  headingRef: RefObject<HTMLHeadingElement>;
  status: string | undefined;
  statusLabel?: string | undefined;
  startedAt?: string | undefined;
  finishedAt?: string | undefined;
  steps: readonly RunStepResponse[];
  stopControl?: ReactNode;
}

const ACTIVE_STATUSES = new Set(['pending', 'running', 'cancelling']);

function runBadgeState(
  status: string | undefined,
): 'running' | 'needs-you' | 'completed' | 'error' | 'stopped' | 'pending' {
  if (status === 'running' || status === 'cancelling') return 'running';
  if (status === 'waiting_approval') return 'needs-you';
  if (status === 'completed') return 'completed';
  if (status === 'error') return 'error';
  if (status === 'cancelled') return 'stopped';
  return 'pending';
}

/** Single-row run header pairing heading, status badge, elapsed clock, stop button, and X/N progress bar. */
export function ProductVideoRunHeader({
  title,
  headingRef,
  status,
  statusLabel,
  startedAt,
  finishedAt,
  steps,
  stopControl,
}: Readonly<ProductVideoRunHeaderProps>) {
  const { t } = useTranslation();
  const isActive = ACTIVE_STATUSES.has(status ?? '');
  const elapsed = useElapsedClock(startedAt, finishedAt, isActive);

  const total = steps.length;
  const completedCount = steps.filter((s) => s.status === 'completed').length;
  const activeIndex = steps.findIndex(
    (s) => s.status === 'running' || (status === 'waiting_approval' && s.status === 'pending'),
  );
  const currentStepNum =
    activeIndex >= 0 ? activeIndex + 1 : Math.min(Math.max(completedCount, 1), Math.max(total, 1));

  const runningStep = activeIndex >= 0 ? steps[activeIndex] : undefined;
  const activeFraction =
    runningStep?.status === 'running' && typeof runningStep.progressPercent === 'number'
      ? Math.max(0, Math.min(1, runningStep.progressPercent / 100))
      : 0;
  const percent =
    total > 0 ? Math.min(100, Math.round(((completedCount + activeFraction) / total) * 100)) : 0;
  const visualBarWidth =
    completedCount === 0 && isActive && percent < 8 ? 8 : Math.max(0, Math.min(100, percent));

  const badgeState = runBadgeState(status);

  return (
    <header className="pv-run-header">
      <div className="pv-run-topbar">
        <div className="pv-run-title-group">
          <PageHeading ref={headingRef} title={title} />
          {statusLabel && (
            <span className="run-step-status pv-run-badge" data-state={badgeState}>
              <StatusGlyph state={badgeState} />
              {statusLabel}
            </span>
          )}
        </div>
        {(elapsed || stopControl) && (
          <div className="pv-run-actions-group">
            {elapsed && (
              <span className="pv-elapsed-pill">
                <Clock size={14} aria-hidden="true" />
                <span>{elapsed}</span>
              </span>
            )}
            {stopControl}
          </div>
        )}
      </div>

      {total > 0 && (
        <div
          className="pv-overall-strip"
          aria-label={t('workflow.run.stepProgress', { current: currentStepNum, total })}
        >
          <div className="pv-overall-meta">
            <span className="pv-overall-steps">
              {t('workflow.run.stepProgress', { current: currentStepNum, total })}
            </span>
            <span className="pv-overall-pct">{`${percent}%`}</span>
          </div>
          <div className="pv-overall-track">
            <div className="pv-overall-fill" style={{ width: `${visualBarWidth}%` }} />
          </div>
        </div>
      )}
    </header>
  );
}
