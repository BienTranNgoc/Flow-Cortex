import { ApiError } from '@flow-cortex/api-client';
import { formatDateTime, type Locale } from '@flow-cortex/i18n';
import type { TFunction } from 'i18next';

const LOCK_TIME_FORMAT: Intl.DateTimeFormatOptions = { dateStyle: 'short', timeStyle: 'short' };

export type TotpFailure =
  | { kind: 'invalid' }
  | { kind: 'locked'; lockedUntil: string | undefined }
  | { kind: 'expired' }
  | { kind: 'unavailable' }
  | { kind: 'restart' }
  | { kind: 'alreadyEnabled' }
  | { kind: 'other'; detail: string | undefined };

/** The state a code, setup or sign-in request ended in, read from the problem's status and code. */
export function totpFailure(error: unknown): TotpFailure {
  if (!(error instanceof ApiError)) return { kind: 'other', detail: undefined };
  const code = error.problem?.code;
  if (error.status === 423) return { kind: 'locked', lockedUntil: error.problem?.lockedUntil };
  if (error.status === 422 && code === 'TOTP_INVALID_CODE') return { kind: 'invalid' };
  if (error.status === 401 && code === 'TOTP_CHALLENGE_INVALID') return { kind: 'expired' };
  if (error.status === 503 && code === 'TOTP_UNAVAILABLE') return { kind: 'unavailable' };
  if (error.status === 409 && code === 'TOTP_NOT_SET_UP') return { kind: 'restart' };
  if (error.status === 409 && code === 'TOTP_ALREADY_ENABLED') return { kind: 'alreadyEnabled' };
  return { kind: 'other', detail: error.problem?.detail };
}

/** The sentence for a failure, in the active language; a lock names its end in that language's date form. */
export function totpFailureMessage(failure: TotpFailure, t: TFunction, locale: Locale): string {
  switch (failure.kind) {
    case 'invalid':
      return t('auth.totp.invalidCode');
    case 'locked': {
      const end = failure.lockedUntil === undefined ? Number.NaN : Date.parse(failure.lockedUntil);
      return Number.isNaN(end)
        ? t('auth.totp.lockedLater')
        : t('auth.totp.locked', { time: formatDateTime(end, locale, LOCK_TIME_FORMAT) });
    }
    case 'expired':
      return t('auth.totp.expired');
    case 'unavailable':
      return t('auth.totp.unavailable');
    case 'restart':
      return t('auth.totp.setupGone');
    case 'alreadyEnabled':
      return t('auth.totp.alreadyEnabled');
    case 'other':
      return failure.detail || t('common.errors.requestFailed');
  }
}
