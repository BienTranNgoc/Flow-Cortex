import { ApiError } from '@flow-cortex/api-client';
import type { TFunction } from 'i18next';
import { formatDateTime } from './format';
import type { Locale } from './locale';

const LOCK_TIME_FORMAT: Intl.DateTimeFormatOptions = { dateStyle: 'short', timeStyle: 'short' };

/**
 * In Vietnamese, the account-locked sentence with the lock end in vi-VN form;
 * otherwise nothing, so English keeps the server `detail`.
 */
export function accountLockedMessage(
  error: unknown,
  t: TFunction,
  locale: Locale,
): string | undefined {
  if (locale !== 'vi' || !(error instanceof ApiError)) return undefined;
  const problem = error.problem ?? {};
  if (typeof problem.type !== 'string' || !problem.type.endsWith('account-locked')) {
    return undefined;
  }
  if (typeof problem.lockedUntil !== 'string') return undefined;
  const lockedUntil = new Date(problem.lockedUntil);
  if (Number.isNaN(lockedUntil.getTime())) return undefined;
  return t('auth.login.accountLocked', {
    time: formatDateTime(lockedUntil, 'vi', LOCK_TIME_FORMAT),
  });
}
