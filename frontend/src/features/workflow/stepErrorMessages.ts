import type { TFunction } from 'i18next';
import type { Locale } from '../../lib/i18n';

/** Every `errorCode` the server writes on a step, with the key of the sentence a member reads for it. */
const SENTENCE_KEYS: Record<string, string> = {
  input_rejected: 'workflow.stepError.input_rejected',
  probe_failed: 'workflow.stepError.probe_failed',
  lease_expired: 'workflow.stepError.lease_expired',
  queue_timeout: 'workflow.stepError.queue_timeout',
  run_timeout: 'workflow.stepError.run_timeout',
  media_disabled: 'workflow.stepError.media_disabled',
  contract_violation: 'workflow.stepError.contract_violation',
  speech_too_long: 'workflow.stepError.speech_too_long',
  APPROVAL_REJECTED: 'workflow.stepError.APPROVAL_REJECTED',
};

/** The reaper closes inline steps with these codes too, and those never reached the media engine. */
const REAPED_CODES: ReadonlySet<string> = new Set([
  'queue_timeout',
  'lease_expired',
  'run_timeout',
]);
const REAPED_INLINE = 'workflow.stepError.reapedInline';

/** Codes whose server message is written for members and carries specifics the sentence lacks. */
const MEMBER_MESSAGE_CODES: ReadonlySet<string> = new Set(['speech_too_long']);

export const NO_MESSAGE = 'workflow.stepError.noMessage';

/**
 * A step failure in the member's words; an unknown code shows the server's message as is. A reaped
 * inline step gets the neutral sentence, and the English-only member message is used only in English.
 */
export function stepErrorSentence(
  code: string | undefined,
  message: string | undefined,
  remote: boolean,
  t: TFunction,
  locale: Locale,
): string {
  if (code && REAPED_CODES.has(code) && !remote) {
    return t(REAPED_INLINE);
  }
  if (code && MEMBER_MESSAGE_CODES.has(code) && message && locale === 'en') {
    return message;
  }
  const key = code ? SENTENCE_KEYS[code] : undefined;
  if (key) {
    return t(key);
  }
  return message ?? t(NO_MESSAGE);
}
