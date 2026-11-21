import type { RunHistoryRowResponse } from '@flow-cortex/api-client';
import type { TFunction } from 'i18next';
import { formatDateTime } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';

/** The member's own title, else the workflow's name. */
export function runName(run: Pick<RunHistoryRowResponse, 'title' | 'workflowName'>): string {
  return run.title?.trim() ? run.title : run.workflowName;
}

export function formatStart(startedAt: string): string {
  return formatDateTime(startedAt, activeLocale(), { dateStyle: 'medium', timeStyle: 'short' });
}

/** Seconds as the shortest readable span: `45s`, `2m 30s`, `1h 5m`. */
export function formatSpan(seconds: number, t: TFunction): string {
  const whole = Math.max(0, Math.round(seconds));
  if (whole < 60) return t('common.units.secondsShort', { value: whole });
  const minutes = Math.floor(whole / 60);
  const rest = whole % 60;
  if (minutes < 60) {
    return rest > 0
      ? t('common.units.minutesSeconds', { minutes, seconds: rest })
      : t('common.units.minutes', { value: minutes });
  }
  const hours = Math.floor(minutes / 60);
  const remainder = minutes % 60;
  return remainder > 0
    ? t('common.units.hoursMinutes', { hours, minutes: remainder })
    : t('common.units.hours', { value: hours });
}
