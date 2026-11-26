import { ApiError } from '@flow-cortex/api-client';
import type { ProblemDetail } from '@flow-cortex/api-client';
import type { Locale } from '@flow-cortex/i18n';
import { formatDateTime } from '../../lib/format';

/** A six-digit authenticator code, spaces allowed. */
export const TOTP_CODE_SHAPE = /^[0-9 ]{6,7}$/;
/** A recovery code: ten base32 characters, spaces and hyphens allowed. */
export const RECOVERY_CODE_SHAPE = /^[A-Za-z2-7 -]{10,13}$/;

export type TotpFailure =
  | { kind: 'invalid' }
  | { kind: 'locked'; lockedUntil: Date | null }
  | { kind: 'expired' }
  | { kind: 'unavailable' }
  | { kind: 'unknown' };

/** Names what a refused second-factor request means for the screen, from its status and problem code. */
export function totpFailure(status: number, problem: ProblemDetail | undefined): TotpFailure {
  if (status === 422 && problem?.code === 'TOTP_INVALID_CODE') {
    return { kind: 'invalid' };
  }
  if (status === 423) {
    return { kind: 'locked', lockedUntil: lockEnd(problem?.lockedUntil) };
  }
  if (status === 401 && problem?.code === 'TOTP_CHALLENGE_INVALID') {
    return { kind: 'expired' };
  }
  if (status === 503) {
    return { kind: 'unavailable' };
  }
  return { kind: 'unknown' };
}

/** {@link totpFailure} for whatever a mutation threw; anything that is not an API refusal is unknown. */
export function totpFailureFrom(error: unknown): TotpFailure {
  return error instanceof ApiError ? totpFailure(error.status, error.problem) : { kind: 'unknown' };
}

/** The end of a lock in the reader's local time; the date is added only when it is not today. */
export function lockEndText(until: Date, locale: Locale, now: Date = new Date()): string {
  const sameDay = until.toDateString() === now.toDateString();
  return formatDateTime(
    until,
    locale,
    sameDay ? { timeStyle: 'short' } : { dateStyle: 'medium', timeStyle: 'short' },
  );
}

function lockEnd(lockedUntil: string | undefined): Date | null {
  const date = lockedUntil === undefined ? null : new Date(lockedUntil);
  return date && !Number.isNaN(date.getTime()) ? date : null;
}
