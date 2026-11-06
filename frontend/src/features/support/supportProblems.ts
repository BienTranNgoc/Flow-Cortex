import type { TFunction } from 'i18next';
import i18n from '../../lib/i18n';

const PROBLEM_CODES = new Set([
  'support-disabled',
  'article-not-found',
  'assistant-quota',
  'assistant-paused',
  'assistant-disabled',
  'assistant-unavailable',
  'question-too-long',
  'attachment-rejected',
  'attachments-unavailable',
  'request-not-found',
  'request-closed',
  'transition-not-allowed',
  'request-state-changed',
  'idempotency-key-reused',
]);

/** The member-facing message for a support problem `code`, or `undefined` for a code with none. */
export function problemMessage(
  code: string | undefined,
  options: { resumeAt?: string } = {},
  t: TFunction = i18n.t,
): string | undefined {
  return code && PROBLEM_CODES.has(code) ? t(`support.problems.${code}`, options) : undefined;
}
